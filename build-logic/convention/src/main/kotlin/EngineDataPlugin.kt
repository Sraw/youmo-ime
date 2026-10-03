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
 * Mixed into libime's language model are the n-grams of the chat-like pages of two of FineWeb-2's
 * Chinese shards (ime-dict-tool's `mix`, which picks the pages): libime's model knows written
 * text, and people type chat. The sentence models, `engine/sentence-model.safetensors` (4M,
 * weighing the readings at each key) and `engine/sentence-model-large.safetensors` (25M, while the
 * user pauses), are this fork's own (dev/TRAINING-PLAN.md: FineWeb-2 pages scored by
 * Qwen3.5-9B-Base), fetched from a release of the fork. Beside libime's dictionary go 万象拼音's
 * (rime_wanxiang, CC-BY-4.0), read as Rime writes them: libime's has few words of the last years.
 * It stands in until the fork finds its own new words, and `-Pengine.wanxiang=false` leaves it out. The .data files and the models are
 * stored uncompressed, so the engine can map them straight out of the APK rather than copying
 * them out first; they are also left out of the data descriptor for that reason (the descriptor lists only src/main/assets).
 *
 * The sources are the archives libime's CMake downloaded, checked against the same SHA-256.
 * FineWeb-2's are pinned to a revision of the dataset and checked against the SHA-256 it lists.
 * Downloads go to the root project's `.gradle/engine-downloads`, out of `build`: ten gigabytes
 * that `clean` should not fetch again, and where one put there by hand is not taken for a stale
 * output and deleted.
 * Each step declares its inputs and outputs, so the downloads, the seven-minute mix and the
 * compile run once and again only when a source or the tool changes. `-Pengine.mix=false` compiles
 * libime's model as it is, skipping FineWeb-2 altogether: for CI, whose runners have neither the
 * memory nor the disk for it, and whose builds are not released.
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
        // HuggingFaceFW/fineweb-2's cmn_Hani (ODC-By 1.0), at the revision measured: shard name to
        // SHA-256. Two of its 370 shards hold some 0.4 billion characters of chat-like pages; more
        // is a question for measuring (dev/ENGINE-DESIGN.md)
        private const val FINEWEB_URL =
            "https://huggingface.co/datasets/HuggingFaceFW/fineweb-2/resolve/af9c13333eb981300149d5ca60a8e9d659b276b9/data/cmn_Hani/train/"
        private val FINEWEB = mapOf(
            "000_00000.parquet" to "3e43fefabc3ee500f9874655ece1776f96b81568cf33e0a6376835425ce42598",
            "000_00001.parquet" to "1829410bee959d64fee8c34efd3e741f22c368cfe62aaa7a971a56afabe1d92f",
        )
        // amzxyz/rime_wanxiang's dictionaries at the commit measured (dev/TRAINING-PLAN.md 10.5): name to SHA-256
        private const val WANXIANG_URL =
            "https://raw.githubusercontent.com/amzxyz/rime_wanxiang/55fbad487c637d64a0371b74d177302ac1cdd16e/dicts/"
        private val WANXIANG = mapOf(
            "zi.dict.yaml" to "4be1b3689bb6a5e9316583b3aa2e8c1cb83a33a7efe3775568e957212943b35a",
            "jichu.dict.yaml" to "99c09968033e4a8e4e73f9e1cca7240ddb48a2af75cf2e745659a0b534e4c502",
            "lianxiang.dict.yaml" to "46ad9ba434e5f1c5e2a38adefa3a8d542d26aa1c9eb588407bea82a9240e1c3f",
            "duoyin.dict.yaml" to "36d3110e14cc58910bcb586cef0c0cb193d4f571332baf517932c39d9498f9ac",
            "diming.dict.yaml" to "627b351e6fa660a40cef86dd923a4bafdeb0bc052c3f2b5eb36600b0d839e0d2",
            "renming.dict.yaml" to "4c171aa4f5608934f5c504d8435c0e85dc06b938a1337a1fb041819dca1ca631",
        )
        const val MIX_TASK = "mixEngineModel"
        const val MODEL_TASK = "copySentenceModels"
        // a tag of its own, not "latest": what is downloaded is what was measured
        private const val MODEL_URL =
            "https://github.com/Sraw/youmo-ime/releases/download/sentence-models-20261001/"
        // asset name (the release's and the app's) to SHA-256
        private val MODELS = mapOf(
            "sentence-model.safetensors" to "342ae775e1ee64b6c42af782f55f736c5bf58b904f3b880ee77a576e2268e7fb",
            "sentence-model-large.safetensors" to "6f7fcb724738e2fbe4c26db70fd53aa5b5729cb4af7ce7f1e7bfea73003e669a",
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

        val shards = FINEWEB.entries.mapIndexed { i, (name, sha) ->
            target.tasks.register<DownloadTask>("downloadFineweb$i") {
                url.set(FINEWEB_URL + name)
                sha256.set(sha)
                outputFile.set(downloadsDir.file("fineweb2/$name"))
            }
        }

        val wanxiang = WANXIANG.entries.mapIndexed { i, (name, sha) ->
            target.tasks.register<DownloadTask>("downloadWanxiang$i") {
                url.set(WANXIANG_URL + name)
                sha256.set(sha)
                outputFile.set(downloadsDir.file("wanxiang/$name"))
            }
        }

        val tool = target.configurations.create("engineDataTool") {
            isCanBeConsumed = false
            isCanBeResolved = true
            // the app is an Android project: without this the JVM tool's variants do not match
            attributes.attribute(Usage.USAGE_ATTRIBUTE, target.objects.named(Usage.JAVA_RUNTIME))
        }
        target.dependencies.add(tool.name, target.dependencies.project(mapOf("path" to ":lib:ime-dict-tool")))

        val mix = target.tasks.register<MixEngineModel>(MIX_TASK) {
            classpath = tool
            mainClass.set(TOOL_MAIN)
            // both models and the pages' counts: 7 GB resident, as measured; keep in step with
            // ime-dict-tool's own run task
            maxHeapSize = "6g"
            lm.set(extracted[0].flatMap { it.outputDir.file(LM.files.single()) })
            corpus.from(shards.map { download -> download.flatMap { it.outputFile } })
            output.set(target.layout.buildDirectory.file("engine-model-mixed/lm_mixed.arpa"))
        }
        val compile = target.tasks.register<CompileEngineData>(COMPILE_TASK) {
            classpath = tool
            mainClass.set(TOOL_MAIN)
            // the whole mixed model is held in memory while it is sorted: 3.6 GB resident, as measured
            maxHeapSize = "4g"
            if (!flag(target, "engine.mix")) {
                lm.set(extracted[0].flatMap { it.outputDir.file(LM.files.single()) })
            } else {
                lm.set(mix.flatMap { it.output })
            }
            dictionaries.from(DICT.files.map { name -> extracted[1].flatMap { it.outputDir.file(name) } })
            if (flag(target, "engine.wanxiang")) dictionaries.from(wanxiang.map { download -> download.flatMap { it.outputFile } })
            outputDir.set(target.layout.buildDirectory.dir("generated/engine-assets"))
        }
        val tables = target.tasks.register<CompileTables>(TABLES_TASK) {
            classpath.from(tool)
            tables.from(TABLE.files.map { name -> extracted[2].flatMap { it.outputDir.file(name) } })
            outputDir.set(target.layout.buildDirectory.dir("generated/engine-tables"))
        }
        val models = MODELS.entries.mapIndexed { i, (name, sha) ->
            target.tasks.register<DownloadTask>("downloadSentenceModel$i") {
                url.set(MODEL_URL + name)
                sha256.set(sha)
                outputFile.set(downloadsDir.file("sentence-models/$name"))
            }
        }
        val model = target.tasks.register<CopyModels>(MODEL_TASK) {
            files.from(models.map { task -> task.flatMap { it.outputFile } })
            outputDir.set(target.layout.buildDirectory.dir("generated/engine-models"))
        }

        target.extensions.configure<ApplicationExtension> {
            // matched as a plain suffix: "data" alone would catch charselectdata too; and the
            // sentence models: openFd cannot read one compressed, and the engine takes that for none
            androidResources.noCompress += listOf(".data", ".safetensors")
        }
        components.onVariants { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(compile, CompileEngineData::outputDir)
            variant.sources.assets?.addGeneratedSourceDirectory(tables, CompileTables::outputDir)
            variant.sources.assets?.addGeneratedSourceDirectory(model, CopyModels::outputDir)
        }
    }

    /** A `-P<name>=true|false` switch, on unless said otherwise. */
    private fun flag(target: Project, name: String) = target.providers.gradleProperty(name).orNull?.let {
        requireNotNull(it.toBooleanStrictOrNull()) { "$name: true or false, not $it" }
    } ?: true

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

    /** The language model mixed with the n-grams of web pages, as ARPA text (a gigabyte). */
    @CacheableTask
    abstract class MixEngineModel : JavaExec() {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val lm: RegularFileProperty

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val corpus: ConfigurableFileCollection

        @get:OutputFile
        abstract val output: RegularFileProperty

        init {
            argumentProviders += CommandLineArgumentProvider {
                listOf("mix", "-o", output.get().asFile.path, "--lm", lm.get().asFile.path) + corpus.files.map { it.path }
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
