/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkerExecutor
import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.inject.Inject

/**
 * What the voice builds (the `voice` flavor of the `speech` dimension) add to the text ones: the
 * speech recognizer and its models, none of them in a text build, which asks for no microphone
 * either.
 *
 * - sherpa-onnx (k2-fsa, Apache-2.0), the recognizer: the :lib:sherpa-onnx module, built from
 *   source, ours to change.
 * - X-ASR zh-en (Gilgamesh-J, Apache-2.0), int8, as sherpa-onnx exports it: a Zipformer transducer
 *   trained on some million hours, Mandarin and English with punctuation. Of the models sherpa-onnx
 *   runs, the most accurate on the voice evaluation sets (AISHELL-1, FLEURS, ASCEND mixed speech)
 *   but for those five to fifteen times slower, the fastest, and the smallest; and, a transducer,
 *   it takes hotwords. Numbers it writes in characters: VoiceText puts them in digits. Its encoder
 *   is fine-tuned by us for accented Mandarin (KeSpeech's dialect speech 17.1% -> 14.6% wrong,
 *   standard sets unchanged), on synthesized dialect speech held to the original by anchor batches.
 * - silero VAD (MIT): where a stretch of speech ends.
 * - Hotwords: the new-word pack (EngineDataPlugin's `downloadEngineWords`), each word's characters
 *   apart by spaces as the dict tool's `hotwords` writes them (HotwordSpelling.spelled), and the
 *   model's BPE vocabulary, which sherpa-onnx encodes them with, read out of its SentencePiece model.
 *
 * The transducer goes in as onnxruntime's own format (`.ort`), converted from sherpa-onnx's export
 * by the release `voice-models-*` of this fork (its manifest.txt says how): the recognizer uses it
 * where it lies in the APK, mapped, rather than reading it onto the heap (lib/sherpa-onnx README).
 *
 * Downloaded once, SHA-256 checked (DownloadTask), into the voice variants'
 * assets under `voice/`, uncompressed: mapped, or for the VAD read whole, as the recognizer loads them.
 */
class VoiceDataPlugin : Plugin<Project> {

    companion object {
        /** the app's flavor dimension, text or voice, and the flavor of the voice builds */
        const val DIMENSION = "speech"
        const val FLAVOR = "voice"
        internal const val MODEL_NAME = "sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03"
        private const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$MODEL_NAME.tar.bz2"
        private const val MODEL_SHA256 = "5d02c36d7b44e886b7c8f0d8e051f8713acab96c264bb6ef9e718be39a6a2224"

        /** the archive's files the assets take as they are; its bpe.model is read for bpe.vocab */
        internal val MODEL_FILES = listOf("tokens.txt")
        internal const val BPE_MODEL = "bpe.model"

        // the archive's decoder and joiner, and its encoder fine-tuned for accented Mandarin (the
        // release's manifest.txt), converted by onnxruntime 1.30's convert_onnx_models_to_ort, the
        // version the recognizer is built with
        private const val ORT_RELEASE = "voice-models-20261017"

        // the archive ORT_RELEASE was converted from: another archive's tokens would go with these weights
        private const val ORT_MODEL = "sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03"
        private const val ORT_URL = "https://github.com/Sraw/youmo-ime/releases/download/$ORT_RELEASE/x-asr-ort.tar.zst"
        private const val ORT_SHA256 = "89416b38b51238674c19df4eb6c9d2356917e77e5276a028ce87b13234f785bf"
        internal const val ORT_DIR = "x-asr-ort"
        internal val ORT_FILES = listOf("encoder.ort", "decoder.ort", "joiner.ort")
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
        val components = target.extensions.getByType<ApplicationAndroidComponentsExtension>()

        val modelArchive = target.tasks.register<DownloadTask>("downloadVoiceModel") {
            url.set(MODEL_URL)
            sha256.set(MODEL_SHA256)
            outputFile.set(downloadsDir.file("$MODEL_NAME.tar.bz2"))
        }
        val modelDir = target.tasks.register<ExtractTask>("extractVoiceModel") {
            archive.set(modelArchive.flatMap { it.outputFile })
            // the weights come converted from ORT_RELEASE: of the archive's 176 MB, the 0.2 MB the assets read
            members.set((MODEL_FILES + BPE_MODEL).map { "$MODEL_NAME/$it" })
            cmake.set(target.sdkCmake)
            outputDir.set(target.layout.buildDirectory.dir("voice-sources"))
        }
        val ortArchive = target.tasks.register<DownloadTask>("downloadVoiceOrt") {
            url.set(ORT_URL)
            sha256.set(ORT_SHA256)
            outputFile.set(downloadsDir.file("$ORT_RELEASE-x-asr-ort.tar.zst"))
        }
        val ortDir = target.tasks.register<ExtractTask>("extractVoiceOrt") {
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
        val assets = target.tasks.register<CopyVoiceModels>("copyVoiceModels") {
            model.set(modelDir.flatMap { it.outputDir })
            ort.set(ortDir.flatMap { it.outputDir })
            this.vad.set(vad.flatMap { it.outputFile })
            this.hotwords.set(hotwords.flatMap { it.outputFile })
            outputDir.set(target.layout.buildDirectory.dir("generated/voice-assets"))
        }

        // the flavor's configuration exists once the build script has declared the flavors
        target.configurations.matching { it.name == "${FLAVOR}Implementation" }.configureEach {
            dependencies.add(target.dependencies.project(mapOf("path" to ":lib:sherpa-onnx")))
        }
        target.extensions.configure<ApplicationExtension> {
            androidResources.noCompress += listOf(".onnx", ".ort")
        }
        components.onVariants(components.selector().withFlavor(DIMENSION to FLAVOR)) { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(assets, CopyVoiceModels::outputDir)
        }
    }

    /** The pack's words as the recognizer's hotwords, a line each: the dict tool's `hotwords`, so the app's rule spells them. */
    @CacheableTask
    abstract class CompileHotwords @Inject constructor(private val workers: WorkerExecutor) : DefaultTask() {
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

    /** The model (ORT) and its tokens, the VAD and the hotwords, as `voice/` in the assets. */
    @DisableCachingByDefault(because = "linking is quicker than the cache would be")
    abstract class CopyVoiceModels : DefaultTask() {
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val model: DirectoryProperty

        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val ort: DirectoryProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val vad: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val hotwords: RegularFileProperty

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun copy() {
            val out = outputDir.get().asFile.resolve("voice")
            out.deleteRecursively()
            out.mkdirs()
            val dir = model.get().asFile.resolve(MODEL_NAME)
            for (name in MODEL_FILES) link(dir.resolve(name), out.resolve(name))
            for (name in ORT_FILES) link(ort.get().asFile.resolve(ORT_DIR).resolve(name), out.resolve(name))
            vad.get().asFile.copyTo(out.resolve("silero_vad.onnx"))
            out.resolve("bpe.vocab").writeText(vocabulary(dir.resolve(BPE_MODEL).readBytes()))
            hotwords.get().asFile.copyTo(out.resolve("hotwords.txt"))
        }

        // some 175 MB already unpacked in the build directory: a second name for it, not a second copy
        private fun link(from: File, to: File) {
            try {
                Files.createLink(to.toPath(), from.toPath())
            } catch (e: IOException) {
                from.copyTo(to)
            } catch (e: UnsupportedOperationException) {
                from.copyTo(to)
            }
        }

        /**
         * A SentencePiece model's pieces and their scores, `piece<TAB>score` a line, as
         * sherpa-onnx's bpe_vocab has them (its export_bpe_vocab.py, without the protobuf
         * library): ModelProto's field 1, each a SentencePiece of piece (1) and score (2).
         */
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
