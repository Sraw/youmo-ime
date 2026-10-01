/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Usage
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Compiles the own engine's data (lib/ime-dict-tool) from the text sources libime builds its own
 * from and adds it to the app's assets as `engine/pinyin.data` and `engine/table/<name>.data`
 * for libime's code tables (五笔, 仓颉 ...).
 * No sentence model ships, nor is chat mixed into the language model: the ones measured
 * (chinese-ime-lm's, ime-dict-tool's `mix` over LCCC) learned from LCCC, whose README keeps it
 * to research. Stored uncompressed, so the
 * engine can map it straight out of the APK rather than copying it out first; it is also left
 * out of the data descriptor for that reason (the descriptor lists only src/main/assets).
 *
 * The sources are the archives libime's CMake downloaded, checked against the same SHA-256.
 * Downloads go to the root project's `.gradle/engine-downloads`, out of `build`: half a gigabyte
 * that `clean` should not fetch again, and where one put there by hand is not taken for a stale
 * output and deleted.
 * Each step declares its inputs and outputs, so the download and the minute-long compile run
 * once and again only when a source or the tool changes.
 */
class EngineDataPlugin : Plugin<Project> {

    private class Source(val name: String, val sha256: String, val files: List<String>)

    companion object {
        const val COMPILE_TASK = "compileEngineData"
        private const val BASE_URL = "https://download.fcitx-im.org/data/"

        // as libime's data/CMakeLists.txt had them (libime 1.1.15-14-g65003b6, before it left the app)
        private val LM = Source(
            "lm_sc.arpa-20260629.tar.zst",
            "06808333b9173e5374cf2cb5afc12d08f5625bf9abb536489cac376fc05f2e7f",
            listOf("lm_sc.arpa"),
        )
        private val DICT = Source(
            "dict-20260703.tar.zst",
            "c686cab6df8964c48d596f57d205bac31fc72870b06a83017e44503df8c09697",
            listOf("dict_sc.txt", "dict_extb.txt"),
        )
        private val TABLE = Source(
            "table-20240108.tar.zst",
            "3e9d87b04a393f131723472c8eaa860dd23c378a3d4f6a9005513b2a95b3614b",
            listOf("cj", "db", "erbi", "qxm", "wanfeng", "wbpy", "wbx", "zrm").map { "$it.txt" },
        )
        const val TABLES_TASK = "compileEngineTables"
        private const val TOOL_MAIN = "org.fcitx.fcitx5.android.dicttool.MainKt"
    }

    override fun apply(target: Project) {
        val sourcesDir = target.layout.buildDirectory.dir("engine-sources")
        val downloadsDir = target.rootProject.layout.projectDirectory.dir(".gradle/engine-downloads")
        val components = target.extensions.getByType<ApplicationAndroidComponentsExtension>()
        val cmakeVersion = target.cmakeVersion
        val extracted = listOf(LM, DICT, TABLE).map { source ->
            val download = target.tasks.register<DownloadTask>("download" + taskName(source)) {
                url.set(BASE_URL + source.name)
                sha256.set(source.sha256)
                outputFile.set(downloadsDir.file(source.name))
            }
            target.tasks.register<ExtractTask>("extract" + taskName(source)) {
                archive.set(download.flatMap { it.outputFile })
                cmake.set(components.sdkComponents.sdkDirectory.map { it.file("cmake/$cmakeVersion/bin/cmake") })
                outputDir.set(sourcesDir.map { it.dir(stem(source)) })
            }
        }

        val tool = target.configurations.create("engineDataTool") {
            isCanBeConsumed = false
            isCanBeResolved = true
            // the app is an Android project: without this the JVM tool's variants do not match
            attributes.attribute(Usage.USAGE_ATTRIBUTE, target.objects.named(Usage.JAVA_RUNTIME))
        }
        target.dependencies.add(tool.name, target.dependencies.project(mapOf("path" to ":lib:ime-dict-tool")))

        val compile = target.tasks.register<CompileEngineData>(COMPILE_TASK) {
            classpath = tool
            mainClass.set(TOOL_MAIN)
            // the whole language model is held in memory while it is sorted: 1.2 GB at most, as
            // measured
            maxHeapSize = "2g"
            lm.set(extracted[0].flatMap { it.outputDir.file(LM.files.single()) })
            dictionaries.from(DICT.files.map { name -> extracted[1].flatMap { it.outputDir.file(name) } })
            outputDir.set(target.layout.buildDirectory.dir("generated/engine-assets"))
        }
        val tables = target.tasks.register<CompileTables>(TABLES_TASK) {
            classpath.from(tool)
            tables.from(TABLE.files.map { name -> extracted[2].flatMap { it.outputDir.file(name) } })
            outputDir.set(target.layout.buildDirectory.dir("generated/engine-tables"))
        }

        target.extensions.configure<ApplicationExtension> {
            // matched as a plain suffix: "data" alone would catch charselectdata too
            // and a sentence model, should one ship again: openFd cannot read one compressed, and
            // the engine takes that for none shipped
            androidResources.noCompress += listOf(".data", ".safetensors")
        }
        components.onVariants { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(compile, CompileEngineData::outputDir)
            variant.sources.assets?.addGeneratedSourceDirectory(tables, CompileTables::outputDir)
        }
    }

    private fun stem(source: Source) = source.name.substringBefore('-').substringBefore('.')

    private fun taskName(source: Source) = stem(source).split('_').joinToString("") { it.replaceFirstChar(Char::uppercase) }

    /** Fetches [url] into [outputFile], failing unless its SHA-256 is [sha256]. */
    @DisableCachingByDefault(because = "a download: fetching it again is what the cache would save")
    abstract class DownloadTask : DefaultTask() {
        @get:Input
        abstract val url: Property<String>

        @get:Input
        abstract val sha256: Property<String>

        @get:OutputFile
        abstract val outputFile: RegularFileProperty

        @TaskAction
        fun download() {
            val out = outputFile.get().asFile
            // put there by hand (offline), or by a build whose history is gone
            if (out.isFile && sha256Of(out) == sha256.get()) return
            val partial = File(out.path + ".part")
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = URI(url.get()).toURL().openConnection() as HttpURLConnection
            // without these a stalled connection hangs the build for good
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            try {
                DigestInputStream(connection.inputStream, digest).use { input -> partial.outputStream().use { input.copyTo(it) } }
            } catch (e: IOException) {
                partial.delete()
                throw IllegalStateException(
                    "cannot download ${url.get()} (${e.message}); offline, put it at $out (SHA-256 ${sha256.get()})",
                    e,
                )
            } finally {
                connection.disconnect()
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != sha256.get()) {
                partial.delete()
                throw IllegalStateException("${url.get()}: SHA-256 $actual, expected ${sha256.get()}")
            }
            Files.move(partial.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        private fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(file.inputStream(), digest).use { it.copyTo(OutputStream.nullOutputStream()) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private companion object {
            const val CONNECT_TIMEOUT_MS = 30_000
            const val READ_TIMEOUT_MS = 60_000
        }
    }

    /** Unpacks a .tar.zst with the SDK's CMake: the native build needs it anyway, unlike zstd. */
    @DisableCachingByDefault(because = "unpacking is quicker than the cache would be")
    abstract class ExtractTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val archive: RegularFileProperty

        @get:Internal
        abstract val cmake: RegularFileProperty

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun extract() {
            val cmake = cmake.get().asFile
            // AGP installs CMake only for the native build, which may not have run yet
            check(cmake.exists() || File(cmake.path + ".exe").exists()) {
                "no CMake at $cmake: install it with sdkmanager \"cmake;${cmake.parentFile.parentFile.name}\""
            }
            val dir = outputDir.get().asFile
            dir.deleteRecursively()
            dir.mkdirs()
            exec.exec {
                commandLine(cmake.path, "-E", "tar", "xf", archive.get().asFile.path)
                workingDir = dir
            }
        }
    }

    @CacheableTask
    abstract class CompileEngineData : JavaExec() {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val lm: RegularFileProperty

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val dictionaries: ConfigurableFileCollection

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        init {
            argumentProviders += CommandLineArgumentProvider {
                listOf("pinyin", "-o", output().path, "--lm", lm.get().asFile.path) + dictionaries.files.map { it.path }
            }
        }

        private fun output() = outputDir.get().asFile.resolve("engine/pinyin.data")

        override fun exec() {
            output().parentFile.mkdirs()
            super.exec()
        }
    }

    /** Compiles each code table's text into `engine/table/<name>.data`, one run of the tool each. */
    @CacheableTask
    abstract class CompileTables @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
        @get:Classpath
        abstract val classpath: ConfigurableFileCollection

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val tables: ConfigurableFileCollection

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun compile() {
            val out = outputDir.get().asFile.resolve("engine/table")
            out.deleteRecursively()
            out.mkdirs()
            for (table in tables.files) {
                exec.javaexec {
                    classpath = this@CompileTables.classpath
                    mainClass.set(TOOL_MAIN)
                    // a table is a few MB of text; the default heap is a quarter of the machine's
                    maxHeapSize = "512m"
                    args("table", "-o", out.resolve(table.nameWithoutExtension + ".data").path, table.path)
                }
            }
        }
    }
}
