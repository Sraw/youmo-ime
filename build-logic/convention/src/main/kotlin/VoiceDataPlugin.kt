/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkerExecutor
import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.inject.Inject

/** Voice input: the recognizer in every build, its models in the voice pack, from [LOCAL_PROPERTY]'s directory held to [listing]. */
class VoiceDataPlugin : Plugin<Project> {

    companion object {
        const val COPY_TASK = "copyVoiceModels"
        internal const val MODEL_NAME = "sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03"
        private const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$MODEL_NAME.tar.bz2"
        private const val MODEL_SHA256 = "5d02c36d7b44e886b7c8f0d8e051f8713acab96c264bb6ef9e718be39a6a2224"

        /** the model's files the voice pack takes as they are; its bpe.model is read for bpe.vocab */
        internal val MODEL_FILES = listOf("tokens.txt")
        internal const val BPE_MODEL = "bpe.model"

        // our fine-tuned encoder with the archive's decoder and joiner, converted to .ort at the recognizer's onnxruntime version
        private const val ORT_RELEASE = "voice-models-20261017"

        // the archive ORT_RELEASE was converted from: another archive's tokens would go with these weights
        private const val ORT_MODEL = "sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03"
        private const val ORT_URL = "https://github.com/Sraw/youmo-ime/releases/download/$ORT_RELEASE/x-asr-ort.tar.zst"
        private const val ORT_SHA256 = "89416b38b51238674c19df4eb6c9d2356917e77e5276a028ce87b13234f785bf"
        internal val ORT_FILES = listOf("encoder.ort", "decoder.ort", "joiner.ort")

        /** A machine's own directory of the voice models (~/.gradle/gradle.properties), held to [listing]. */
        const val LOCAL_PROPERTY = "youmo.voiceModelDir"

        internal val TAGGER_FILES = listOf("polish-tagger.ort", "polish-tagger.json")

        /** the model files voice pack [version] has of [LOCAL_PROPERTY]'s directory, by SHA-256 (the root project's) */
        internal fun listing(version: Int) = "gradle/voice-pack-v$version.sha256"

        // whether a stretch heard is speech at all (VoiceQuiet)
        private const val VAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
        private const val VAD_SHA256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
    }

    override fun apply(target: Project) {
        // tokens of one model with another's weights decode to nonsense, and nothing else would fail
        check(ORT_MODEL == MODEL_NAME) {
            "$ORT_RELEASE is $ORT_MODEL converted, not $MODEL_NAME: publish $MODEL_NAME's as a new voice-models release and pin it"
        }
        // its downloadEngineWords and its tool make the hotwords, whichever plugin the build script names first
        target.pluginManager.apply(EngineDataPlugin::class.java)
        val downloadsDir = target.rootProject.layout.projectDirectory.dir(".gradle/engine-downloads/voice")

        val modelArchive = target.tasks.register<DownloadTask>("downloadVoiceModel") {
            url.set(MODEL_URL)
            sha256.set(MODEL_SHA256)
            outputFile.set(downloadsDir.file("$MODEL_NAME.tar.bz2"))
        }
        target.tasks.register<ExtractTask>("extractVoiceModel") {
            archive.set(modelArchive.flatMap { it.outputFile })
            // the weights come converted from ORT_RELEASE: only the small files the assets read
            members.set((MODEL_FILES + BPE_MODEL).map { "$MODEL_NAME/$it" })
            cmake.set(target.sdkCmake)
            outputDir.set(target.layout.buildDirectory.dir("voice-sources"))
        }
        val ortArchive = target.tasks.register<DownloadTask>("downloadVoiceOrt") {
            url.set(ORT_URL)
            sha256.set(ORT_SHA256)
            outputFile.set(downloadsDir.file("$ORT_RELEASE-x-asr-ort.tar.zst"))
        }
        target.tasks.register<ExtractTask>("extractVoiceOrt") {
            archive.set(ortArchive.flatMap { it.outputFile })
            cmake.set(target.sdkCmake)
            outputDir.set(target.layout.buildDirectory.dir("voice-ort"))
        }
        val vad = target.tasks.register<DownloadTask>("downloadVoiceVad") {
            url.set(VAD_URL)
            sha256.set(VAD_SHA256)
            outputFile.set(downloadsDir.file("silero_vad.onnx"))
        }
        val words = target.tasks.named<DownloadTask>(EngineDataPlugin.WORDS_TASK)
        val hotwords = target.tasks.register<CompileHotwords>("compileVoiceHotwords") {
            classpath.from(target.configurations.named(EngineDataPlugin.TOOL_CONFIGURATION))
            toolCode.set(target.tasks.named<EngineDataPlugin.FingerprintTool>(EngineDataPlugin.FINGERPRINT_TASK).flatMap { it.outputFile })
            this.words.set(words.flatMap { it.outputFile })
            outputFile.set(target.layout.buildDirectory.file("generated/voice-hotwords/hotwords.txt"))
        }
        val localDir = target.providers.gradleProperty(LOCAL_PROPERTY)
        val version = packsProperty(packsProperties(target), "voice.version")
        target.tasks.register<CopyVoiceModels>(COPY_TASK) {
            // nothing downloaded for the model without it: copy() then fails, saying where it comes from
            if (localDir.isPresent) {
                local.set(target.layout.projectDirectory.dir(homed(localDir.get(), System.getProperty("user.home"))))
            }
            expected.set(target.rootProject.layout.projectDirectory.file(listing(version)))
            this.hotwords.set(hotwords.flatMap { it.outputFile })
            this.vad.set(vad.flatMap { it.outputFile })
            outputDir.set(target.layout.buildDirectory.dir("generated/voice-assets"))
        }

        target.dependencies.add("implementation", target.dependencies.project(mapOf("path" to ":lib:sherpa-onnx")))
    }

    /** The pack's words as the recognizer's hotwords, a line each, spelled by the dict tool as the app spells them. */
    @CacheableTask
    abstract class CompileHotwords @Inject constructor(private val workers: WorkerExecutor) : DefaultTask() {
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
        abstract val words: RegularFileProperty

        @get:OutputFile
        abstract val outputFile: RegularFileProperty

        @TaskAction
        fun compile() {
            workers.noIsolation().submit(EngineDataPlugin.RunTool::class.java) {
                classpath.from(this@CompileHotwords.classpath)
                heap.set("256m")
                args.set(listOf("hotwords", "-o", outputFile.get().asFile.path, words.get().asFile.path))
            }
        }
    }

    /** The model, tokens and tagger (from [local], as [expected] lists them), the hotwords and the VAD, as the voice pack's `voice/`. */
    @DisableCachingByDefault(because = "linking is quicker than the cache would be")
    abstract class CopyVoiceModels : DefaultTask() {
        /** [LOCAL_PROPERTY]'s directory (`~/` taken as home): without it [copy] fails */
        @get:Optional
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val local: DirectoryProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val expected: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val hotwords: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val vad: RegularFileProperty

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun copy() {
            val out = outputDir.get().asFile.resolve("voice")
            out.deleteRecursively()
            out.mkdirs()
            // the pinned release is the offline model, which the streaming recognizer cannot load
            val local = checkNotNull(local.orNull?.asFile) {
                "voice: the streaming model and the polish tagger come from $LOCAL_PROPERTY alone for now (in ~/.gradle/gradle.properties); the pinned release is the offline model"
            }
            check(local, expected.get().asFile)
            for (name in MODEL_FILES) link(local.resolve(name), out.resolve(name))
            for (name in ORT_FILES) link(local.resolve(name), out.resolve(name))
            for (name in TAGGER_FILES) link(local.resolve(name), out.resolve(name))
            out.resolve("bpe.vocab").writeText(vocabulary(local.resolve(BPE_MODEL).readBytes()))
            hotwords.get().asFile.copyTo(out.resolve("hotwords.txt"))
            vad.get().asFile.copyTo(out.resolve("silero_vad.onnx"))
        }

        // the directory is not in the repo: another version's model would ship silently
        private fun check(local: File, listing: File) {
            val sums = listing.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
                val fields = line.trim().split(Regex("\\s+"), limit = 2)
                if (fields.size < 2) throw GradleException("voice: ${listing.name}'s line '$line' is not `<sha256>  <file>`")
                fields[1].removePrefix("*") to fields[0]
            }
            for (name in MODEL_FILES + ORT_FILES + TAGGER_FILES + BPE_MODEL) {
                val sum = sums[name] ?: throw GradleException("voice: ${listing.name} does not list $name")
                val file = local.resolve(name)
                val actual = if (file.isFile) sha256Of(file) else "missing"
                // shasum writes lower case; a listing written by hand may not
                if (!actual.equals(sum, ignoreCase = true)) {
                    throw GradleException("voice: $LOCAL_PROPERTY's $name ($actual) is not the one ${listing.name} lists ($sum): point $LOCAL_PROPERTY at that pack's models")
                }
            }
        }

        // a large file in the local directory: a second name for it, not a second copy
        private fun link(from: File, to: File) {
            try {
                Files.createLink(to.toPath(), from.toPath())
            } catch (e: IOException) {
                from.copyTo(to)
            } catch (e: UnsupportedOperationException) {
                from.copyTo(to)
            }
        }

        /** A SentencePiece model's pieces as `piece<TAB>score` lines, as sherpa-onnx's export_bpe_vocab.py writes, without protobuf. */
        private fun vocabulary(model: ByteArray): String {
            val out = StringBuilder()
            Proto(model).fields { field, piece ->
                if (field != 1 || piece !is ByteArray) return@fields
                var text = ""
                var score = 0f
                Proto(piece).fields { f, v ->
                    if (f == 1 && v is ByteArray) text = String(v, Charsets.UTF_8)
                    if (f == 2 && v is Int) score = Float.fromBits(v)
                }
                out.append(text).append('\t').append(score).append('\n')
            }
            return out.toString()
        }
    }

    /** The fields of a protobuf message: a varint as a Long, fixed32 as an Int, bytes as bytes. */
    private class Proto(private val bytes: ByteArray) {
        private var at = 0

        private fun varint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                val b = bytes[at++].toInt()
                value = value or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
        }

        private fun fixed(n: Int): Long {
            var value = 0L
            for (i in 0 until n) value = value or ((bytes[at++].toLong() and 0xff) shl (8 * i))
            return value
        }

        fun fields(each: (Int, Any) -> Unit) {
            while (at < bytes.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                val value: Any = when ((key and 7).toInt()) {
                    0 -> varint()
                    1 -> fixed(8)
                    2 -> varint().toInt().let { n -> bytes.copyOfRange(at, at + n).also { at += n } }
                    5 -> fixed(4).toInt()
                    else -> error("protobuf wire type ${key and 7} in a SentencePiece model")
                }
                each(field, value)
            }
        }
    }
}
