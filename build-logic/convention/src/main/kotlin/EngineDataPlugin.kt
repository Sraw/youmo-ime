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
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.process.ExecOperations
import org.gradle.process.JavaExecSpec
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Compiles the own engine's data (lib/ime-dict-tool) and adds it to the app's assets as
 * `engine/pinyin.data` and `engine/table/<name>.data` for libime's code tables (五笔, 仓颉 ...).
 * The language model is this fork's own, fetched from its `engine-data-*` release: libime's,
 * mixed with the n-grams of chat-like web pages (FineWeb-2's and a CommonCrawl crawl's), which
 * lib/ime-dict-tool/engine-data.sh makes on a rented machine, a job of an hour that downloads a
 * hundred gigabytes and so no part of a build. Compiled with it is
 * libime's dictionary and, as a layer of its own, the words of the last years it lacks: the fork's
 * curated lexicon (`lexicon/`), packed for that model and published as a `words-*` release. The
 * sentence models, `engine/sentence-model.safetensors` (4M, weighing the readings at each key) and
 * `engine/sentence-model-large.safetensors` (25M, while the user pauses), are this fork's own too
 * (trained on FineWeb-2 pages scored by Qwen3.5-9B-Base), from another release. English's words
 * and model, `engine/english.data`, are compiled from the trigram model of FineWeb's English pages
 * the tool's `english-lm` made, an `english-data-*` release. The
 * .data files and the models are stored uncompressed, so the engine can map them straight out of
 * the APK rather than copying them out first; they are also left out of the data descriptor for
 * that reason (the descriptor lists only src/main/assets).
 *
 * Every download is checked against a SHA-256: libime's archives against the ones its CMake had,
 * the releases' files against the ones they were published with. Downloads go to the root
 * project's `.gradle/engine-downloads`, out of `build`: what `clean` should not fetch again, and
 * where one put there by hand is not taken for a stale output and deleted.
 * Each step declares its inputs and outputs, so the downloads and the compile run once and again
 * only when a source or the tool changes. Local builds and CI's compile the same data.
 */
class EngineDataPlugin : Plugin<Project> {

    /** What a download task fetches, its SHA-256, and its path under the downloads directory. */
    private class Download(val url: String, val sha256: String, val file: String)

    private class Source(val name: String, sha256: String, val files: List<String>) {
        val download = Download(BASE_URL + name, sha256, name)
    }

    companion object {
        const val COMPILE_TASK = "compileEngineData"
        const val DOWNLOADS_TASK = "downloadEngineData"
        private const val BASE_URL = "https://download.fcitx-im.org/data/"
        private const val RELEASES_URL = "https://github.com/Sraw/youmo-ime/releases/download/"

        // as libime's data/CMakeLists.txt had them (libime 1.1.15-14-g65003b6, before it left the app)
        private val DICT = Source(
            "dict-20260703.tar.zst",
            "c686cab6df8964c48d596f57d205bac31fc72870b06a83017e44503df8c09697",
            listOf("dict_sc.txt", "dict_extb.txt"),
        )
        private val TABLE = Source(
            "table-20240108.tar.zst",
            "3e9d87b04a393f131723472c8eaa860dd23c378a3d4f6a9005513b2a95b3614b",
            listOf("cj", "erbi", "wbpy", "wbx", "zrm").map { "$it.txt" },
        )
        // the language model engine-data.sh made (its manifest.txt in the release says from what),
        // repacked as the tar CMake unpacks; a tag of its own, not "latest", as the models'
        private const val LM_RELEASE = "engine-data-20261005-3"
        private val LM = Download(
            "$RELEASES_URL$LM_RELEASE/lm.tar.zst",
            "be95e2528a7c3e0b0d466e9547399a72b05c21131e09f156ac10fa5d84052b67",
            "engine-data/$LM_RELEASE-lm.tar.zst",
        )
        private const val LM_FILE = "lm.arpa"
        const val WORDS_TASK = "downloadEngineWords"
        // the curated new words (lexicon/add.tsv) as packed for that model, a layer of pinyin.data:
        // the words libime's dictionary has not got, which a build with no pack the user put in has too
        private const val WORDS_RELEASE = "words-202610"
        private val WORDS = Download(
            "$RELEASES_URL$WORDS_RELEASE/youmo-new.words",
            "92fec7e7b8d0e9540676a87a27e93d824428e170e7bcf183a5c9af924dc52b76",
            "engine-data/$WORDS_RELEASE-youmo-new.words",
        )

        // rime-stroke (LGPL-3.0): characters by their strokes, for pinyin's u lookup
        private const val STROKES_COMMIT = "1e8fff9b9494ddec23b0cbc526bcfd8171a6fd48"
        private val STROKES = Download(
            "https://raw.githubusercontent.com/rime/rime-stroke/$STROKES_COMMIT/stroke.dict.yaml",
            "b3e93dce89c185f45c3d6e189b86b3a8626913352cc85e1094c786579a665791",
            "engine-data/stroke-$STROKES_COMMIT.dict.yaml",
        )
        const val ENGLISH_TASK = "compileEnglishData"
        // FineWeb's English pages, counted by lib/ime-dict-tool's english-lm and pruned by its prune
        // at 3e-8 (its manifest.txt in the release says from what and how): the arpa the tool
        // compiles, in a tar CMake unpacks
        private const val ENGLISH_RELEASE = "english-data-20261015"
        private val ENGLISH = Download(
            "$RELEASES_URL$ENGLISH_RELEASE/english.tar.zst",
            "e582610acbc45bf9b70667c893fd80dd605c7484aa1e64577ef7c6e7ae32b4d0",
            "engine-data/$ENGLISH_RELEASE-english.tar.zst",
        )
        private const val ENGLISH_FILE = "english.arpa"

        const val MODEL_TASK = "copySentenceModels"
        // a tag of its own, not "latest": what is downloaded is what was measured
        private const val MODELS_RELEASE = "sentence-models-20261001"
        private const val MODEL_URL = "$RELEASES_URL$MODELS_RELEASE/"
        // asset name (the release's and the app's) to SHA-256
        private val MODELS = mapOf(
            "sentence-model.safetensors" to "342ae775e1ee64b6c42af782f55f736c5bf58b904f3b880ee77a576e2268e7fb",
            "sentence-model-large.safetensors" to "6f7fcb724738e2fbe4c26db70fd53aa5b5729cb4af7ce7f1e7bfea73003e669a",
        )
        const val TABLES_TASK = "compileEngineTables"
        // each table run is a JVM of its own, up to 1 GB, beside the daemon's 2 GB (org.gradle.jvmargs in
        // gradle.properties): a few at once, not as many as Gradle has workers, so the six leave room for an emulator
        private const val TABLE_RUNS_AT_ONCE = 3
        const val TOOL_CONFIGURATION = "engineDataTool"
        const val FINGERPRINT_TASK = "fingerprintEngineDataTool"
        private const val TOOL_MAIN = "org.fcitx.fcitx5.android.dicttool.MainKt"

        /** A run of the tool built as [tool], in a JVM of [heap]. */
        private fun JavaExecSpec.configureTool(tool: FileCollection, heap: String, command: List<String>) {
            classpath = tool
            mainClass.set(TOOL_MAIN)
            maxHeapSize = heap
            args(command)
        }
    }

    override fun apply(target: Project) {
        // every download registerDownload makes, none of the voice's: what engine_downloads.yml runs to fill its cache
        target.tasks.register(DOWNLOADS_TASK)
        val sourcesDir = target.layout.buildDirectory.dir("engine-sources")
        val downloadsDir = target.rootProject.layout.projectDirectory.dir(".gradle/engine-downloads")
        val components = target.extensions.getByType<ApplicationAndroidComponentsExtension>()
        val dict = registerSource(target, DICT, downloadsDir, sourcesDir)
        val table = registerSource(target, TABLE, downloadsDir, sourcesDir)
        val lmDownload = registerDownload(target, "downloadEngineModel", LM, downloadsDir)
        val lm = registerExtract(target, "extractEngineModel", lmDownload, sourcesDir.map { it.dir("lm_mixed") })
        val words = registerDownload(target, WORDS_TASK, WORDS, downloadsDir)
        val strokes = registerDownload(target, "downloadStrokes", STROKES, downloadsDir)
        val englishDownload = registerDownload(target, "downloadEnglishModel", ENGLISH, downloadsDir)
        val englishLm = registerExtract(target, "extractEnglishModel", englishDownload, sourcesDir.map { it.dir("english") })

        val tool = target.configurations.create(TOOL_CONFIGURATION) {
            isCanBeConsumed = false
            isCanBeResolved = true
            // the app is an Android project: without this the JVM tool's variants do not match
            attributes.attribute(Usage.USAGE_ATTRIBUTE, target.objects.named(Usage.JAVA_RUNTIME))
        }
        target.dependencies.add(tool.name, target.dependencies.project(mapOf("path" to ":lib:ime-dict-tool")))

        // the tool's code that a run reaches, not all of :lib:ime-core it is built with (ToolFingerprint);
        // a task's output, so it is read once, after the jars are built, the configuration cache on or off
        val fingerprint = target.tasks.register<FingerprintTool>(FINGERPRINT_TASK) {
            classpath.from(tool)
            mainClass.set(TOOL_MAIN)
            outputFile.set(target.layout.buildDirectory.file("engine-tool.fingerprint"))
        }
        val compile = target.tasks.register<CompileEngineData>(COMPILE_TASK) {
            classpath.from(tool)
            toolCode.set(fingerprint.flatMap { it.outputFile })
            this.lm.set(lm.flatMap { it.outputDir.file(LM_FILE) })
            dictionaries.from(DICT.files.map { name -> dict.flatMap { it.outputDir.file(name) } })
            dictionaries.from(words.flatMap { it.outputFile })
            // the base layer as this project corrects it: libime's words wrongly there, readings put right
            val lexicon = target.rootProject.layout.projectDirectory.dir("lexicon")
            remove.set(lexicon.file("remove.tsv"))
            readings.set(lexicon.file("readings.tsv"))
            misreadings.set(lexicon.file("misreadings.tsv"))
            // the Latin words typed inside Chinese (iPhone, APP), a layer of their own: lexicon/tools/latin.py
            dictionaries.from(lexicon.file("latin.words"))
            outputDir.set(target.layout.buildDirectory.dir("generated/engine-assets"))
        }
        val english = target.tasks.register<CompileEnglishData>(ENGLISH_TASK) {
            classpath.from(tool)
            toolCode.set(fingerprint.flatMap { it.outputFile })
            this.lm.set(englishLm.flatMap { it.outputDir.file(ENGLISH_FILE) })
            outputDir.set(target.layout.buildDirectory.dir("generated/engine-english"))
        }
        val tableRuns = target.gradle.sharedServices.registerIfAbsent("engineTableRuns", TableRuns::class.java)
        val tables = target.tasks.register<CompileTables>(TABLES_TASK) {
            classpath.from(tool)
            toolCode.set(fingerprint.flatMap { it.outputFile })
            tables.from(TABLE.files.map { name -> table.flatMap { it.outputDir.file(name) } })
            this.words.set(words.flatMap { it.outputFile })
            this.strokes.set(strokes.flatMap { it.outputFile })
            pinyin.set(compile.flatMap { it.outputDir.file("engine/pinyin.data") })
            runsAtOnce.set(tableRuns)
            usesService(tableRuns)
            outputDir.set(target.layout.buildDirectory.dir("generated/engine-tables"))
        }
        val models = MODELS.entries.mapIndexed { i, (name, sha) ->
            registerDownload(target, "downloadSentenceModel$i", Download(MODEL_URL + name, sha, "sentence-models/$name"), downloadsDir)
        }
        val model = target.tasks.register<CopyModels>(MODEL_TASK) {
            files.from(models.map { task -> task.flatMap { it.outputFile } })
            outputDir.set(target.layout.buildDirectory.dir("generated/engine-models"))
        }

        target.extensions.configure<ApplicationExtension> {
            // matched as a plain suffix: "data" alone would catch charselectdata too; and the
            // sentence models: openFd cannot read one compressed, and the engine takes that for none
            androidResources.noCompress += listOf(".data", ".safetensors")
            // what the settings show as the engine's data, and the release a missing-word report names
            defaultConfig.buildConfigField("String", "ENGINE_DATA", "\"$LM_RELEASE\"")
            // the releases of the word pack and of the sentence models, which change apart from it
            defaultConfig.buildConfigField("String", "ENGINE_WORDS", "\"$WORDS_RELEASE\"")
            defaultConfig.buildConfigField("String", "SENTENCE_MODELS", "\"$MODELS_RELEASE\"")
            defaultConfig.buildConfigField("String", "ENGLISH_DATA", "\"$ENGLISH_RELEASE\"")
        }
        components.onVariants { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(compile, CompileEngineData::outputDir)
            variant.sources.assets?.addGeneratedSourceDirectory(english, CompileEnglishData::outputDir)
            variant.sources.assets?.addGeneratedSourceDirectory(tables, CompileTables::outputDir)
            variant.sources.assets?.addGeneratedSourceDirectory(model, CopyModels::outputDir)
        }
    }

    private fun stem(source: Source) = source.name.substringBefore('-').substringBefore('.')

    private fun taskName(source: Source) = stem(source).split('_').joinToString("") { it.replaceFirstChar(Char::uppercase) }

    private fun registerDownload(target: Project, name: String, download: Download, downloads: Directory) =
        target.tasks.register<DownloadTask>(name) {
            url.set(download.url)
            sha256.set(download.sha256)
            outputFile.set(downloads.file(download.file))
        }.also { task -> target.tasks.named(DOWNLOADS_TASK).configure { dependsOn(task) } }

    private fun registerExtract(target: Project, name: String, download: TaskProvider<DownloadTask>, into: Provider<Directory>) =
        target.tasks.register<ExtractTask>(name) {
            archive.set(download.flatMap { it.outputFile })
            cmake.set(target.sdkCmake)
            outputDir.set(into)
        }

    /** One of libime's archives, downloaded and unpacked under [sources], as `extract<Stem>`. */
    private fun registerSource(target: Project, source: Source, downloads: Directory, sources: Provider<Directory>): TaskProvider<ExtractTask> {
        val download = registerDownload(target, "download" + taskName(source), source.download, downloads)
        return registerExtract(target, "extract" + taskName(source), download, sources.map { it.dir(stem(source)) })
    }

    /** Writes the [ToolFingerprint] of [classpath] run from [mainClass], what the compiles are keyed on. */
    @DisableCachingByDefault(because = "walking the classes is quicker than the cache would be")
    abstract class FingerprintTool : DefaultTask() {
        @get:Classpath
        abstract val classpath: ConfigurableFileCollection

        @get:Input
        abstract val mainClass: Property<String>

        @get:OutputFile
        abstract val outputFile: RegularFileProperty

        @TaskAction
        fun fingerprint() {
            outputFile.get().asFile.writeText(ToolFingerprint.of(classpath.files, mainClass.get()))
        }
    }

    @CacheableTask
    abstract class CompileEngineData @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
        // built before this runs, but keyed on by toolCode only
        @get:Internal
        abstract val classpath: ConfigurableFileCollection

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val toolCode: RegularFileProperty

        init {
            dependsOn(classpath)
        }

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val lm: RegularFileProperty

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val dictionaries: ConfigurableFileCollection

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val remove: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val readings: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val misreadings: RegularFileProperty

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        private fun output() = outputDir.get().asFile.resolve("engine/pinyin.data")

        @TaskAction
        fun compile() {
            output().parentFile.mkdirs()
            exec.javaexec {
                classpath = this@CompileEngineData.classpath
                mainClass.set(TOOL_MAIN)
                // the whole mixed model is held in memory while it is sorted: 3.6 GB resident, as measured
                maxHeapSize = "4g"
                args(
                    listOf(
                        "pinyin", "-o", output().path, "--lm", lm.get().asFile.path,
                        "--remove", remove.get().asFile.path, "--readings", readings.get().asFile.path,
                        "--misreadings", misreadings.get().asFile.path,
                    ) + dictionaries.files.map { it.path }
                )
            }
        }
    }

    /** English's words and model as `engine/english.data`, compiled from the arpa of the release. */
    @CacheableTask
    abstract class CompileEnglishData @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
        // built before this runs, but keyed on by toolCode only
        @get:Internal
        abstract val classpath: ConfigurableFileCollection

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val toolCode: RegularFileProperty

        init {
            dependsOn(classpath)
        }

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val lm: RegularFileProperty

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun compile() {
            val output = outputDir.get().asFile.resolve("engine/english.data")
            output.parentFile.mkdirs()
            exec.javaexec {
                // the model is held in memory while it is sorted: 2.1 GB resident, as measured
                configureTool(this@CompileEnglishData.classpath, "2g", listOf("english", "-o", output.path, "--lm", lm.get().asFile.path))
            }
        }
    }

    /** The sentence models under `engine/`, named as they were downloaded. */
    @DisableCachingByDefault(because = "copying is quicker than the cache would be")
    abstract class CopyModels : DefaultTask() {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val files: ConfigurableFileCollection

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun copy() {
            val out = outputDir.get().asFile.resolve("engine")
            out.deleteRecursively()
            out.mkdirs()
            for (file in files.files) file.copyTo(out.resolve(file.name), overwrite = true)
        }
    }

    /**
     * Compiles each code table's text into `engine/table/<name>.data`, one run of the tool each, and the
     * strokes into `engine/stroke.data`; the runs side by side, [TABLE_RUNS_AT_ONCE] at a time ([runsAtOnce]).
     */
    @CacheableTask
    abstract class CompileTables @Inject constructor(private val workers: WorkerExecutor) : DefaultTask() {
        @get:Internal
        abstract val classpath: ConfigurableFileCollection

        @get:Internal
        abstract val runsAtOnce: Property<TableRuns>

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val toolCode: RegularFileProperty

        init {
            dependsOn(classpath)
        }

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val tables: ConfigurableFileCollection

        /** The new-word pack, its words added to each table its rules can code them for. */
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val words: RegularFileProperty

        /** The compiled pinyin data, whose words go into the tables too, the commonest first. */
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val pinyin: RegularFileProperty

        /** rime-stroke's dictionary, compiled beside the tables as `engine/stroke.data`. */
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val strokes: RegularFileProperty

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun compile() {
            val out = outputDir.get().asFile.resolve("engine/table")
            out.deleteRecursively()
            out.mkdirs()
            // a table is a few MB of text, the pinyin data mapped, its 800k words listed; the
            // default heap is a quarter of the machine's
            val runs = tables.files.map { table ->
                "1g" to listOf(
                    "table", "-o", out.resolve(table.nameWithoutExtension + ".data").path,
                    "--words", words.get().asFile.path, "--pinyin", pinyin.get().asFile.path, table.path,
                )
            } + ("512m" to listOf("strokes", "-o", out.resolveSibling("stroke.data").path, strokes.get().asFile.path))
            // each run maps the pinyin data and sorts its 800k words anew: side by side, in a few work items that each
            // take the next as one ends, as an item a run would hold one of Gradle's workers while it waits its turn
            val queued = runsAtOnce.get().queue(runs)
            val lanes = workers.noIsolation()
            repeat(minOf(TABLE_RUNS_AT_ONCE, runs.size)) {
                lanes.submit(RunTables::class.java) {
                    classpath.from(this@CompileTables.classpath)
                    tableRuns.set(this@CompileTables.runsAtOnce)
                    queue.set(queued)
                }
            }
        }
    }

    /**
     * The table runs (heap, arguments) a task has queued, which its [TABLE_RUNS_AT_ONCE] work items take one
     * at a time: a build service, as a work item is handed a copy of its parameters and these must be shared.
     */
    abstract class TableRuns : BuildService<BuildServiceParameters.None> {
        private val queues = ConcurrentHashMap<Int, ConcurrentLinkedQueue<Pair<String, List<String>>>>()
        private val ids = AtomicInteger()

        /** Queues [runs] apart from any other task's, under the id it returns. */
        fun queue(runs: List<Pair<String, List<String>>>): Int =
            ids.incrementAndGet().also { queues[it] = ConcurrentLinkedQueue(runs) }

        /** The next run queued under [id]; null once all are taken, the queue then let go. */
        fun next(id: Int): Pair<String, List<String>>? = queues[id]?.poll() ?: run {
            queues.remove(id)
            null
        }

        /** How many tasks' queues are held, each till a work item finds it empty. */
        internal fun queuedTasks(): Int = queues.size
    }

    /** One run of the tool as a work item, as VoiceDataPlugin compiles the hotwords; the tables go by [RunTables]. */
    abstract class RunTool @Inject constructor(private val exec: ExecOperations) : WorkAction<RunTool.Parameters> {
        interface Parameters : WorkParameters {
            val classpath: ConfigurableFileCollection
            val heap: Property<String>
            val args: ListProperty<String>
        }

        override fun execute() {
            exec.javaexec { configureTool(parameters.classpath, parameters.heap.get(), parameters.args.get()) }
        }
    }

    /** A work item running the table runs queued under [Parameters.queue], the next as each ends, till none is left. */
    abstract class RunTables @Inject constructor(private val exec: ExecOperations) : WorkAction<RunTables.Parameters> {
        interface Parameters : WorkParameters {
            val classpath: ConfigurableFileCollection
            val tableRuns: Property<TableRuns>
            val queue: Property<Int>
        }

        override fun execute() {
            val runs = parameters.tableRuns.get()
            val queue = parameters.queue.get()
            generateSequence { runs.next(queue) }.forEach { (heap, args) ->
                exec.javaexec { configureTool(parameters.classpath, heap, args) }
            }
        }
    }
}

/** The CMake that unpacks the downloads: [localCmake], else the SDK's of [cmakeVersion]. */
internal val Project.sdkCmake: Provider<RegularFile>
    get() {
        val sdk = extensions.getByType<ApplicationAndroidComponentsExtension>().sdkComponents.sdkDirectory
        val version = cmakeVersion
        return localCmake.orElse(sdk.map { it.file("cmake/$version/bin/cmake") })
    }

/** The CMake in the directory local.properties' `cmake.dir` names, which AGP's native build takes too. */
internal val Project.localCmake: Provider<RegularFile>
    get() {
        val local = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText
        val dir = local.map { text -> Properties().apply { load(text.reader()) }.getProperty("cmake.dir") }
        return layout.file(dir.map { File(it, "bin/cmake") })
    }
