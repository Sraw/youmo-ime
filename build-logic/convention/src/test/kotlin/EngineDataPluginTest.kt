/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.CacheableTask
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.gradle.process.JavaExecSpec
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.work.DisableCachingByDefault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

class EngineDataPluginTest {

    @get:Rule
    val folder = TemporaryFolder()

    // a class file with no constants, and one with a single text constant: an edit's different bytes
    private val emptyClass = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, 0, 61, 0, 1)
    private val editedClass = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, 0, 61, 0, 2, 1, 0, 1, 'x'.code.toByte())

    private fun fingerprintTask(classes: File, out: File): EngineDataPlugin.FingerprintTool {
        val project = ProjectBuilder.builder().withProjectDir(folder.newFolder("app")).build()
        val task = project.tasks.register("fingerprintEngineDataTool", EngineDataPlugin.FingerprintTool::class.java).get()
        task.classpath.from(classes)
        task.mainClass.set("tool.MainKt")
        task.outputFile.set(out)
        return task
    }

    @Test
    fun theFingerprintTaskWritesWhatTheCompilesAreKeyedOn() {
        val classes = folder.newFolder("classes")
        classes.resolve("tool/MainKt.class").apply { parentFile.mkdirs() }.writeBytes(emptyClass)
        val out = folder.root.resolve("engine-tool.fingerprint")
        val task = fingerprintTask(classes, out)
        task.fingerprint()
        assertEquals(ToolFingerprint.of(listOf(classes), "tool.MainKt"), out.readText())
    }

    @Test
    fun theKeyIsOfTheToolAsItIsWhenTheTaskRuns() {
        val classes = folder.newFolder("classes")
        val main = classes.resolve("tool/MainKt.class").apply { parentFile.mkdirs() }
        val out = folder.root.resolve("engine-tool.fingerprint")
        val task = fingerprintTask(classes, out)
        main.writeBytes(emptyClass)
        task.fingerprint()
        val before = out.readText()
        // the tool built again after the task was set up, as its jars are: keyed when configured, the
        // compiles would run the new tool on the old key
        main.writeBytes(editedClass)
        task.fingerprint()
        assertNotEquals(before, out.readText())
    }

    @Test
    fun theCmakeDirLocalPropertiesNamesIsTheOneUsed() {
        val cmake = folder.newFolder("cmake")
        val dir = folder.newFolder("project")
        dir.resolve("local.properties").writeText("sdk.dir=/opt/android-sdk\ncmake.dir=${cmake.invariantSeparatorsPath}\n")
        val project = ProjectBuilder.builder().withProjectDir(dir).build()
        assertEquals(cmake.resolve("bin/cmake"), project.localCmake.get().asFile)
    }

    @Test
    fun withoutCmakeDirThereIsNone() {
        assertFalse(ProjectBuilder.builder().withProjectDir(folder.newFolder("bare")).build().localCmake.isPresent)
        val sdkOnly = folder.newFolder("sdk-only").apply { resolve("local.properties").writeText("sdk.dir=/opt/android-sdk\n") }
        assertFalse(ProjectBuilder.builder().withProjectDir(sdkOnly).build().localCmake.isPresent)
    }

    /** A task saying neither is never kept in the build cache, which CI restores (english.data compiled again each run). */
    @Test
    fun eachTaskSaysWhetherTheBuildCacheKeepsItTheCompilesThatItDoes() {
        val tasks = EngineDataPlugin::class.java.declaredClasses.filter { DefaultTask::class.java.isAssignableFrom(it) }
        assertTrue(tasks.isNotEmpty())
        for (task in tasks) {
            val says = task.isAnnotationPresent(CacheableTask::class.java) || task.isAnnotationPresent(DisableCachingByDefault::class.java)
            assertTrue(task.simpleName, says)
        }
        for (compile in listOf(EngineDataPlugin.CompileEngineData::class.java, EngineDataPlugin.CompileEnglishData::class.java, EngineDataPlugin.CompileTables::class.java)) {
            assertTrue(compile.simpleName, compile.isAnnotationPresent(CacheableTask::class.java))
        }
    }

    @Test
    fun theWorkItemsTakeEachQueuedTableRunOnceAndNoneOfAnotherTasks() {
        val service = object : EngineDataPlugin.TableRuns() {
            override fun getParameters(): BuildServiceParameters.None = error("it has none")
        }
        // a heap each, so a run started with another's heap shows
        val runs = (1..100).map { "${it}m" to listOf("table", "$it") }
        val tables = service.queue(runs)
        val strokes = service.queue(listOf("512m" to listOf("strokes")))
        val project = ProjectBuilder.builder().withProjectDir(folder.newFolder("app")).build()
        val tool = project.files(folder.newFile("tool.jar"))
        val exec = FakeExec(project.objects)
        // CompileTables' work items, side by side
        val items = List(3) { runTables(exec, project.objects, tool, service, tables) }
        items.map { thread { it.execute() } }.forEach { it.join() }
        assertEquals(runs, exec.runs.sortedBy { it.second[1].toInt() })
        assertEquals(listOf(tool.files), exec.classpaths.distinct())
        // written out, lib/ime-dict-tool's Main.kt: the plugin's TOOL_MAIN naming no class of the tool fails here
        assertEquals(listOf("org.fcitx.fcitx5.android.dicttool.MainKt"), exec.mainClasses.distinct())
        assertEquals("512m" to listOf("strokes"), service.next(strokes))
        assertNull(service.next(strokes))
    }

    @Test
    fun aTasksQueueIsLetGoOnceAWorkItemFindsItEmpty() {
        val service = object : EngineDataPlugin.TableRuns() {
            override fun getParameters(): BuildServiceParameters.None = error("it has none")
        }
        val tables = service.queue(listOf("1g" to listOf("table", "1")))
        service.queue(listOf("512m" to listOf("strokes")))
        assertEquals("1g" to listOf("table", "1"), service.next(tables))
        // its last run taken, and maybe still running
        assertEquals(2, service.queuedTasks())
        assertNull(service.next(tables))
        // the other task's still queued
        assertEquals(1, service.queuedTasks())
        assertNull(service.next(tables))
    }

    // a work item as Gradle would make one, its parameters those CompileTables.compile sets
    private fun runTables(exec: ExecOperations, objects: ObjectFactory, tool: FileCollection, service: EngineDataPlugin.TableRuns, id: Int) =
        object : EngineDataPlugin.RunTables(exec) {
            private val params = object : EngineDataPlugin.RunTables.Parameters {
                override val classpath: ConfigurableFileCollection = objects.fileCollection().from(tool)
                override val tableRuns: Property<EngineDataPlugin.TableRuns> = objects.property(EngineDataPlugin.TableRuns::class.java).value(service)
                override val queue: Property<Int> = objects.property(Int::class.javaObjectType).value(id)
            }

            override fun getParameters(): EngineDataPlugin.RunTables.Parameters = params
        }
}

/** Starts no JVM: keeps each run's main class, heap, arguments and classpath, and has nothing else of a spec. */
private class FakeExec(private val objects: ObjectFactory) : ExecOperations {
    val runs = ConcurrentLinkedQueue<Pair<String?, List<String>>>()
    val classpaths = ConcurrentLinkedQueue<Set<File>>()
    val mainClasses = ConcurrentLinkedQueue<String>()

    override fun exec(action: Action<in ExecSpec>): ExecResult = throw UnsupportedOperationException("exec")

    // one run at a time, ObjectFactory not being documented as thread-safe; the items still take their runs side by side
    override fun javaexec(action: Action<in JavaExecSpec>): ExecResult = synchronized(this) {
        val mainClass = objects.property(String::class.java)
        var classpath = emptySet<File>()
        var heap: String? = null
        val args = ArrayList<String>()
        val spec = Proxy.newProxyInstance(JavaExecSpec::class.java.classLoader, arrayOf(JavaExecSpec::class.java)) { proxy, method, params ->
            when (method.name) {
                "setClasspath" -> proxy.also { classpath = (params[0] as FileCollection).files }
                "getMainClass" -> mainClass
                "setMaxHeapSize" -> {
                    heap = params[0] as String
                    null
                }
                "args" -> proxy.also { args += (params[0] as? Iterable<*> ?: (params[0] as Array<*>).asIterable()).map { it.toString() } }
                // `args = command` instead of args(command): the same run
                "setArgs" -> proxy.also {
                    args.clear()
                    args += (params[0] as Iterable<*>?)?.map { it.toString() }.orEmpty()
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as JavaExecSpec
        action.execute(spec)
        // unset, it throws: the run is not kept, and the runs compared then fall short
        mainClasses.add(mainClass.get())
        runs.add(heap to args)
        classpaths.add(classpath)
        NormalExit
    }
}

private object NormalExit : ExecResult {
    override fun getExitValue() = 0
    override fun assertNormalExitValue(): ExecResult = this
    override fun rethrowFailure(): ExecResult = this
}
