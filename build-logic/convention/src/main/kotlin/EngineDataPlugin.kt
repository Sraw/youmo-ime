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
import org.gradle.api.tasks.Optional
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

/** Compiles the engine's data into `engine/`, stored uncompressed so the engine maps it out of the APK. */
class EngineDataPlugin : Plugin<Project> {

    private class Download(val url: String, val sha256: String, val file: String)

    private class Source(val name: String, sha256: String, val files: List<String>) {
        val download = Download(BASE_URL + name, sha256, name)
    }

    companion object {
        const val COMPILE_TASK = "compileEngineData"

        /** The app's consumable configuration of the engine data's directories (`project(":app", DATA_CONFIGURATION)`). */
        const val DATA_CONFIGURATION = "engineData"
        const val DOWNLOADS_TASK = "downloadEngineData"
        private const val BASE_URL = "https://download.fcitx-im.org/data/"
        private const val RELEASES_URL = "https://github.com/Sraw/youmo-ime/releases/download/"

        // as libime's data/CMakeLists.txt had them (libime 1.1.15-14-g65003b6)
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
        // the language model engine-data.sh made, as the tar CMake unpacks; a fixed tag, not "latest"
        private const val LM_RELEASE = "engine-data-20261005-3"
        private val LM = Download(
            "$RELEASES_URL$LM_RELEASE/lm.tar.zst",
            "be95e2528a7c3e0b0d466e9547399a72b05c21131e09f156ac10fa5d84052b67",
            "engine-data/$LM_RELEASE-lm.tar.zst",
        )
        private const val LM_FILE = "lm.arpa"
        const val WORDS_TASK = "downloadEngineWords"
        // the curated new words (lexicon/add.tsv) packed for that model: a layer of pinyin.data
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
        // the tones: Unihan via mozillazg/pinyin-data and phrase-pinyin-data (MIT); not large_pinyin.txt, which has share-alike and unlicensed data
        private const val UNIHAN_COMMIT = "fe29ad32bdf352b31a02b9936e537f575432e9a6"
        private val UNIHAN = listOf(
            "kMandarin" to "643c38f4a533ac6f25d16390c3b09df41bac8e8f476da88cd18371fdb299198a",
            "kHanyuPinlu" to "260ef08aef066ae5e798ed9933e305094d03a87a5d0bcbced6d740cb9084f2f8",
            "kXHC1983" to "7ade5b5750c92d39dec2151075ebcca323afe6af452349f54438d950b1dd4eeb",
            "kTGHZ2013" to "83be5ecdfe0b8fcc1202ae45882a2f983c013db87ba68369d94a4dad775382bb",
            "kHanyuPinyin" to "edb91d53e81b158242ecc2ea779c70a4ae9391054300eafe7fa60d18ad237504",
        ).map { (name, sha256) ->
            Download(
                "https://raw.githubusercontent.com/mozillazg/pinyin-data/$UNIHAN_COMMIT/$name.txt",
                sha256,
                "engine-data/pinyin-data-$UNIHAN_COMMIT-$name.txt",
            )
        }
        private const val PHRASES_COMMIT = "4afa3226ce53c9138b939e83c3bb294dbe0da511"
        private val PHRASES = Download(
            "https://raw.githubusercontent.com/mozillazg/phrase-pinyin-data/$PHRASES_COMMIT/pinyin.txt",
            "5d4d86b1337bb2fca95bc5135564a821bfdb40597481ee3e3871bf52d4668622",
            "engine-data/phrase-pinyin-data-$PHRASES_COMMIT-pinyin.txt",
        )
        const val ENGLISH_TASK = "compileEnglishData"
        // FineWeb's English pages counted by english-lm and pruned: the arpa the tool compiles
        private const val ENGLISH_RELEASE = "english-data-20261015"
        private val ENGLISH = Download(
            "$RELEASES_URL$ENGLISH_RELEASE/english.tar.zst",
            "e582610acbc45bf9b70667c893fd80dd605c7484aa1e64577ef7c6e7ae32b4d0",
            "engine-data/$ENGLISH_RELEASE-english.tar.zst",
        )
        private const val ENGLISH_FILE = "english.arpa"
        const val EMOJI_TASK = "compileEmojiData"
        // the emoji and the panel's groups (emoji-test.txt) and their Chinese words (CLDR, corrected by lexicon/emoji.tsv)
        private val EMOJI_TEST = Download(
            "https://www.unicode.org/Public/17.0.0/emoji/emoji-test.txt",
            "1d8a944f88d7952f7ef7c5167fef3c67995bcae24543949710231b03a201acda",
            "engine-data/emoji-test-17.0.0.txt",
        )
        private const val CLDR_RELEASE = "release-48-2"
        private val EMOJI_WORDS = Download(
            "https://raw.githubusercontent.com/unicode-org/cldr/$CLDR_RELEASE/common/annotations/zh.xml",
            "92389f71659998736530c72d4dcc86e453fe0be170cde5c5ad27094cab2a2de8",
            "engine-data/cldr-$CLDR_RELEASE-annotations-zh.xml",
        )

        const val CHAIZI_TASK = "compileChaiziData"
        private const val HANDWRITING_PLUGIN = "youmo.handwriting-data"
        // characters by their parts, the open 汉语拆字字典 (CC BY 3.0)
        private const val CHAIZI_COMMIT = "e177ab54ce4edf255315a60593ffecbd8167e96b"
        private val CHAIZI = Download(
            "https://raw.githubusercontent.com/kfcd/chaizi/$CHAIZI_COMMIT/chaizi-jt.txt",
            "c341a99fc6a4a4f3169279c451ca55057a22603a5d7a520094378f65e817ad26",
            "engine-data/chaizi-${CHAIZI_COMMIT.take(8)}-jt.txt",
        )

        const val CHAR_INFO_TASK = "compileCharInfo"
        // each character's radical and strokes for its card: Unihan's kRSUnicode and kTotalStrokes
        private val UNIHAN_ZIP = Download(
            "https://www.unicode.org/Public/17.0.0/ucd/Unihan.zip",
            "f7a48b2b545acfaa77b2d607ae28747404ce02baefee16396c5d2d7a8ef34b5e",
            "engine-data/Unihan-17.0.0.zip",
        )
        private val RADICALS = Download(
            "https://www.unicode.org/Public/17.0.0/ucd/CJKRadicals.txt",
            "826f83be25cd18fb8a5015a514704504e1982e840ea14d058bf583e1cc620c83",
            "engine-data/CJKRadicals-17.0.0.txt",
        )
        const val OPENCC_TASK = "compileOpenccData"
        // each character's group for 生僻字屏蔽 (Unihan kTGH, OpenCC); in the APK, as the groups go with the app's version
        const val CHAR_LEVELS_TASK = "compileCharLevels"

        const val MODEL_TASK = "copySentenceModels"
        // a fixed tag, not "latest": what is downloaded is what was measured
        private const val MODELS_RELEASE = "sentence-models-20261001"
        private const val MODEL_URL = "$RELEASES_URL$MODELS_RELEASE/"
        // asset name (the release's and the app's) to SHA-256
        private val MODELS = mapOf(
            "sentence-model.safetensors" to "342ae775e1ee64b6c42af782f55f736c5bf58b904f3b880ee77a576e2268e7fb",
            "sentence-model-large.safetensors" to "6f7fcb724738e2fbe4c26db70fd53aa5b5729cb4af7ce7f1e7bfea73003e669a",
        )
        const val TABLES_TASK = "compileEngineTables"
        // each table run is a JVM of its own: a few at once, leaving room for an emulator
        private const val TABLE_RUNS_AT_ONCE = 3
        const val TOOL_CONFIGURATION = "engineDataTool"
        const val FINGERPRINT_TASK = "fingerprintEngineDataTool"
        private const val TOOL_MAIN = "org.fcitx.fcitx5.android.dicttool.MainKt"

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
        val unihan = UNIHAN.map { registerDownload(target, "downloadUnihan" + it.file.substringAfterLast("-k").substringBefore("."), it, downloadsDir) }
        val phrases = registerDownload(target, "downloadPhraseTones", PHRASES, downloadsDir)
        val englishDownload = registerDownload(target, "downloadEnglishModel", ENGLISH, downloadsDir)
        val englishLm = registerExtract(target, "extractEnglishModel", englishDownload, sourcesDir.map { it.dir("english") })
        val emojiTest = registerDownload(target, "downloadEmojiTest", EMOJI_TEST, downloadsDir)
        val emojiWords = registerDownload(target, "downloadEmojiWords", EMOJI_WORDS, downloadsDir)
        val chaiziDownload = registerDownload(target, "downloadChaizi", CHAIZI, downloadsDir)
        val unihanZip = registerDownload(target, "downloadUnihanZip", UNIHAN_ZIP, downloadsDir)
        val radicals = registerDownload(target, "downloadCjkRadicals", RADICALS, downloadsDir)

        val tool = target.configurations.create(TOOL_CONFIGURATION) {
            isCanBeConsumed = false
            isCanBeResolved = true
            // the app is an Android project: without this the JVM tool's variants do not match
            attributes.attribute(Usage.USAGE_ATTRIBUTE, target.objects.named(Usage.JAVA_RUNTIME))
        }
        target.dependencies.add(tool.name, target.dependencies.project(mapOf("path" to ":lib:ime-dict-tool")))

        // the tool's code a run reaches (ToolFingerprint); a task output, read once after the jars are built
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
            unihan.forEach { download -> charTones.from(download.flatMap { it.outputFile }) }
            phraseTones.set(phrases.flatMap { it.outputFile })
            tones.set(lexicon.file("tones.tsv"))
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
        val emoji = target.tasks.register<CompileEmojiData>(EMOJI_TASK) {
            classpath.from(tool)
            toolCode.set(fingerprint.flatMap { it.outputFile })
            test.set(emojiTest.flatMap { it.outputFile })
            annotations.set(emojiWords.flatMap { it.outputFile })
            edits.set(target.rootProject.layout.projectDirectory.dir("lexicon").file("emoji.tsv"))
            pinyin.set(compile.flatMap { it.outputDir.file("engine/pinyin.data") })
            outputDir.set(target.layout.buildDirectory.dir("generated/engine-emoji"))
        }
        val chaizi = target.tasks.register<CompileChaiziData>(CHAIZI_TASK) {
            classpath.from(tool)
            toolCode.set(fingerprint.flatMap { it.outputFile })
            source.set(chaiziDownload.flatMap { it.outputFile })
            pinyin.set(compile.flatMap { it.outputDir.file("engine/pinyin.data") })
            outputDir.set(target.layout.buildDirectory.dir("generated/engine-chaizi"))
        }
        val charInfo = target.tasks.register<CompileCharInfo>(CHAR_INFO_TASK) {
            classpath.from(tool)
            toolCode.set(fingerprint.flatMap { it.outputFile })
            this.unihan.set(unihanZip.flatMap { it.outputFile })
            this.radicals.set(radicals.flatMap { it.outputFile })
            this.chaizi.set(chaiziDownload.flatMap { it.outputFile })
            this.strokes.set(strokes.flatMap { it.outputFile })
            // the mainland's stroke orders, this project's (first taken from cnchar, MIT)
            this.orders.set(target.rootProject.layout.projectDirectory.dir("lexicon").file("stroke-order.tsv"))
            pinyin.set(compile.flatMap { it.outputDir.file("engine/pinyin.data") })
            outputDir.set(target.layout.buildDirectory.dir("generated/engine-charinfo"))
        }
        // the characters handwriting knows, each of which must have a card: HandwritingDataPlugin's, applied after this
        target.pluginManager.withPlugin(HANDWRITING_PLUGIN) {
            charInfo.configure {
                cover.set(target.tasks.named<ExtractTask>(HandwritingDataPlugin.EXTRACT_TASK).flatMap { it.outputDir.file("handwriting/chars.txt") })
            }
        }
        val charLevels = target.tasks.register<CompileCharLevels>(CHAR_LEVELS_TASK) {
            classpath.from(tool)
            toolCode.set(fingerprint.flatMap { it.outputFile })
            this.unihan.set(unihanZip.flatMap { it.outputFile })
            traditional.set(target.layout.projectDirectory.file("src/main/assets/learn/TSCharacters.txt"))
            outputDir.set(target.layout.buildDirectory.dir("generated/engine-charlevels"))
        }
        // OpenCC 1.2.0's s2tw dictionaries, committed: frozen data
        val opencc = target.tasks.register<CompileOpenccData>(OPENCC_TASK) {
            classpath.from(tool)
            toolCode.set(fingerprint.flatMap { it.outputFile })
            val dir = target.rootProject.layout.projectDirectory.dir("lib/ime-dict-tool/opencc")
            dictionaries.from(listOf("STPhrases", "STCharacters", "TWVariants").map { dir.file("$it.txt") })
            outputDir.set(target.layout.buildDirectory.dir("generated/engine-opencc"))
        }
        val tableRuns = target.gradle.sharedServices.registerIfAbsent("engineTableRuns", TableRuns::class.java)
        val compileTables = target.tasks.register<CompileTables>(TABLES_TASK) {
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
        val copyModels = target.tasks.register<CopyModels>(MODEL_TASK) {
            files.from(models.map { task -> task.flatMap { it.outputFile } })
            outputDir.set(target.layout.buildDirectory.dir("generated/engine-models"))
        }

        target.extensions.configure<ApplicationExtension> {
            // a plain suffix: "data" alone would catch charselectdata; openFd cannot read a compressed sentence model
            androidResources.noCompress += listOf(".data", ".safetensors")
            defaultConfig.buildConfigField("String", "ENGINE_DATA", "\"$LM_RELEASE\"")
            defaultConfig.buildConfigField("String", "ENGINE_WORDS", "\"$WORDS_RELEASE\"")
            defaultConfig.buildConfigField("String", "SENTENCE_MODELS", "\"$MODELS_RELEASE\"")
            defaultConfig.buildConfigField("String", "ENGLISH_DATA", "\"$ENGLISH_RELEASE\"")
        }
        // in the APK only the small data its code changes with; the rest is the text pack's (AssetPacksPlugin)
        components.onVariants { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(emoji, CompileEmojiData::outputDir)
            variant.sources.assets?.addGeneratedSourceDirectory(chaizi, CompileChaiziData::outputDir)
            variant.sources.assets?.addGeneratedSourceDirectory(charInfo, CompileCharInfo::outputDir)
            variant.sources.assets?.addGeneratedSourceDirectory(charLevels, CompileCharLevels::outputDir)
            variant.sources.assets?.addGeneratedSourceDirectory(opencc, CompileOpenccData::outputDir)
        }
        // every directory of the engine's data, for :lib:ime-eval's replay: a data task added here too, or uatTest misses it
        target.configurations.consumable(DATA_CONFIGURATION)
        listOf(
            compile.flatMap { it.outputDir }, english.flatMap { it.outputDir }, emoji.flatMap { it.outputDir },
            chaizi.flatMap { it.outputDir }, charInfo.flatMap { it.outputDir }, compileTables.flatMap { it.outputDir },
            copyModels.flatMap { it.outputDir }, opencc.flatMap { it.outputDir }, charLevels.flatMap { it.outputDir },
        ).forEach { target.artifacts.add(DATA_CONFIGURATION, it) }
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

    private fun registerSource(target: Project, source: Source, downloads: Directory, sources: Provider<Directory>): TaskProvider<ExtractTask> {
        val download = registerDownload(target, "download" + taskName(source), source.download, downloads)
        return registerExtract(target, "extract" + taskName(source), download, sources.map { it.dir(stem(source)) })
    }

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

        @get:InputFile
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

        // in order: a character's first reading is its usual one
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val charTones: ConfigurableFileCollection

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val phraseTones: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val tones: RegularFileProperty

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        private fun output() = outputDir.get().asFile.resolve("engine/pinyin.data")

        @TaskAction
        fun compile() {
            output().parentFile.mkdirs()
            exec.javaexec {
                classpath = this@CompileEngineData.classpath
                mainClass.set(TOOL_MAIN)
                // the whole mixed model is held in memory while it is sorted
                maxHeapSize = "4g"
                args(
                    listOf(
                        "pinyin", "-o", output().path, "--lm", lm.get().asFile.path,
                        "--remove", remove.get().asFile.path, "--readings", readings.get().asFile.path,
                        "--misreadings", misreadings.get().asFile.path,
                        "--char-tones", charTones.files.joinToString(File.pathSeparator) { it.path },
                        "--phrase-tones", phraseTones.get().asFile.path, "--tones", tones.get().asFile.path,
                    ) + dictionaries.files.map { it.path }
                )
            }
        }
    }

    @DisableCachingByDefault(because = "an abstract base; each compile says whether it is kept")
    abstract class ToolDataTask : DefaultTask() {
        @get:Inject
        abstract val exec: ExecOperations

        @get:Internal
        abstract val classpath: ConfigurableFileCollection

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val toolCode: RegularFileProperty

        init {
            dependsOn(classpath)
        }

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        protected fun runTool(name: String, heap: String, command: (output: String) -> List<String>) {
            val output = outputDir.get().asFile.resolve("engine/$name")
            output.parentFile.mkdirs()
            exec.javaexec { configureTool(this@ToolDataTask.classpath, heap, command(output.path)) }
        }
    }

    @CacheableTask
    abstract class CompileEnglishData : ToolDataTask() {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val lm: RegularFileProperty

        @TaskAction
        fun compile() {
            // the model is held in memory while it is sorted
            runTool("english.data", "2g") { listOf("english", "-o", it, "--lm", lm.get().asFile.path) }
        }
    }

    /** The emoji panel's groups and the Chinese words emoji stand for (EmojiData). */
    @CacheableTask
    abstract class CompileEmojiData : ToolDataTask() {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val test: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val annotations: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val edits: RegularFileProperty

        /** The compiled pinyin data: each word's pinyin for the panel's search, and how common it is. */
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val pinyin: RegularFileProperty

        @TaskAction
        fun compile() {
            runTool("emoji.data", "1g") {
                listOf(
                    "emoji", "-o", it, "--test", test.get().asFile.path, "--lexicon", edits.get().asFile.path,
                    "--pinyin", pinyin.get().asFile.path, annotations.get().asFile.path,
                )
            }
        }
    }

    @CacheableTask
    abstract class CompileChaiziData : ToolDataTask() {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val source: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val pinyin: RegularFileProperty

        @TaskAction
        fun compile() {
            runTool("chaizi.data", "1g") { listOf("chaizi", "-o", it, "--pinyin", pinyin.get().asFile.path, source.get().asFile.path) }
        }
    }

    /** `engine/charinfo.data`: each character's radical, strokes, parts and stroke order, checked against its sources. */
    @CacheableTask
    abstract class CompileCharInfo : ToolDataTask() {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val unihan: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val radicals: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val chaizi: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val strokes: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val orders: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val pinyin: RegularFileProperty

        @get:InputFile
        @get:Optional
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val cover: RegularFileProperty

        @TaskAction
        fun compile() {
            runTool("charinfo.data", "1g") {
                listOf(
                    "charinfo", "-o", it, "--unihan", unihan.get().asFile.path, "--radicals", radicals.get().asFile.path,
                    "--chaizi", chaizi.get().asFile.path, "--strokes", strokes.get().asFile.path,
                    "--orders", orders.get().asFile.path, "--pinyin", pinyin.get().asFile.path,
                ) + (cover.orNull?.let { c -> listOf("--cover", c.asFile.path) } ?: emptyList())
            }
        }
    }

    /** `engine/charlevels.data`: a kTGH that is not the table's 8,105 ranked once each fails. */
    @CacheableTask
    abstract class CompileCharLevels : ToolDataTask() {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val unihan: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val traditional: RegularFileProperty

        @TaskAction
        fun compile() {
            runTool("charlevels.data", "256m") { listOf("charlevels", "-o", it, "--unihan", unihan.get().asFile.path, traditional.get().asFile.path) }
        }
    }

    /** `engine/opencc-s2tw.data`: OpenCC 1.2.0's three s2tw dictionaries, in that order. */
    @CacheableTask
    abstract class CompileOpenccData : ToolDataTask() {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val dictionaries: ConfigurableFileCollection

        // the order is the tool's argument order, which @InputFiles' fingerprint ignores
        @get:Input
        val dictionaryOrder: List<String>
            get() = dictionaries.files.map { it.name }

        @TaskAction
        fun compile() {
            runTool("opencc-s2tw.data", "256m") { listOf("opencc", "-o", it) + dictionaries.files.map { f -> f.path } }
        }
    }

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

    /** Each code table into `engine/table/<name>.data` and the strokes into `engine/stroke.data`, [TABLE_RUNS_AT_ONCE] runs at a time. */
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

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val words: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val pinyin: RegularFileProperty

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
            // the default heap is a quarter of the machine's: each run is given its own
            val runs = tables.files.map { table ->
                "1g" to listOf(
                    "table", "-o", out.resolve(table.nameWithoutExtension + ".data").path,
                    "--words", words.get().asFile.path, "--pinyin", pinyin.get().asFile.path, table.path,
                )
            } + ("512m" to listOf("strokes", "-o", out.resolveSibling("stroke.data").path, strokes.get().asFile.path))
            // a few work items each take the next run as one ends: one item per run would hold a worker while it waits
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

    /** The queued table runs (heap, arguments): a build service, as work items get copies of their parameters and these must be shared. */
    abstract class TableRuns : BuildService<BuildServiceParameters.None> {
        private val queues = ConcurrentHashMap<Int, ConcurrentLinkedQueue<Pair<String, List<String>>>>()
        private val ids = AtomicInteger()

        fun queue(runs: List<Pair<String, List<String>>>): Int =
            ids.incrementAndGet().also { queues[it] = ConcurrentLinkedQueue(runs) }

        /** The next run queued under [id]; null once all are taken, the queue then dropped. */
        fun next(id: Int): Pair<String, List<String>>? = queues[id]?.poll() ?: run {
            queues.remove(id)
            null
        }

        internal fun queuedTasks(): Int = queues.size
    }

    /** One tool run as a work item, as VoiceDataPlugin compiles the hotwords; the tables use [RunTables]. */
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
