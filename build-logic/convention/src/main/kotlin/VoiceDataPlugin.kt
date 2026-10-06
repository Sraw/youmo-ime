/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

/**
 * What the voice builds (the `voice` flavor of the `speech` dimension) add to the text ones: the
 * speech recognizer and its models, none of them in a text build, which asks for no microphone
 * either (dev/TRAINING-PLAN.md 14).
 *
 * - sherpa-onnx's Android library (k2-fsa, Apache-2.0): the recognizer and onnxruntime, JNI for
 *   each ABI; the ABI splits keep the one an APK is for.
 * - X-ASR zh-en (Gilgamesh-J, Apache-2.0), int8, as sherpa-onnx exports it: a Zipformer transducer
 *   trained on some million hours, Mandarin and English with punctuation. Of the models sherpa-onnx
 *   runs, the most accurate on the voice evaluation sets (AISHELL-1, FLEURS, ASCEND mixed speech)
 *   but for those five to fifteen times slower, the fastest, and the smallest; and, a transducer,
 *   it takes hotwords. Numbers it writes in characters: VoiceText puts them in digits.
 * - silero VAD (MIT): where a stretch of speech ends.
 * - Hotwords: the new-word pack (EngineDataPlugin's `downloadEngineWords`), each word's characters
 *   apart by spaces as VoiceHotwords.spelled has them, and the model's BPE vocabulary, which
 *   sherpa-onnx encodes them with, read out of its SentencePiece model.
 *
 * Downloaded once, SHA-256 checked (EngineDataPlugin.DownloadTask), into the voice variants'
 * assets under `voice/`, uncompressed: read whole into memory as the recognizer loads them.
 */
class VoiceDataPlugin : Plugin<Project> {

    companion object {
        const val FLAVOR = "voice"
        private const val SHERPA_VERSION = "1.13.8"
        private const val SHERPA_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar"
        private const val SHERPA_SHA256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
        private const val MODEL_NAME = "sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03"
        private const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$MODEL_NAME.tar.bz2"
        private const val MODEL_SHA256 = "5d02c36d7b44e886b7c8f0d8e051f8713acab96c264bb6ef9e718be39a6a2224"

        /** the archive's files and the names they are given in the assets */
        private val MODEL_FILES = mapOf(
            "encoder-epoch-99-avg-1.int8.onnx" to "encoder.onnx",
            "decoder-epoch-99-avg-1.onnx" to "decoder.onnx",
            "joiner-epoch-99-avg-1.int8.onnx" to "joiner.onnx",
            "tokens.txt" to "tokens.txt",
        )
        private const val VAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
        private const val VAD_SHA256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
    }

    override fun apply(target: Project) {
        val downloadsDir = target.rootProject.layout.projectDirectory.dir(".gradle/engine-downloads/voice")
        val components = target.extensions.getByType<ApplicationAndroidComponentsExtension>()
        val cmakeVersion = target.cmakeVersion

        val sherpa = target.tasks.register<EngineDataPlugin.DownloadTask>("downloadSherpaOnnx") {
            url.set(SHERPA_URL)
            sha256.set(SHERPA_SHA256)
            outputFile.set(downloadsDir.file("sherpa-onnx-$SHERPA_VERSION.aar"))
        }
        val modelArchive = target.tasks.register<EngineDataPlugin.DownloadTask>("downloadVoiceModel") {
            url.set(MODEL_URL)
            sha256.set(MODEL_SHA256)
            outputFile.set(downloadsDir.file("$MODEL_NAME.tar.bz2"))
        }
        val modelDir = target.tasks.register<EngineDataPlugin.ExtractTask>("extractVoiceModel") {
            archive.set(modelArchive.flatMap { it.outputFile })
            cmake.set(components.sdkComponents.sdkDirectory.map { it.file("cmake/$cmakeVersion/bin/cmake") })
            outputDir.set(target.layout.buildDirectory.dir("voice-sources"))
        }
        val vad = target.tasks.register<EngineDataPlugin.DownloadTask>("downloadVoiceVad") {
            url.set(VAD_URL)
            sha256.set(VAD_SHA256)
            outputFile.set(downloadsDir.file("silero_vad.onnx"))
        }
        val words = target.tasks.named<EngineDataPlugin.DownloadTask>("downloadEngineWords")
        val assets = target.tasks.register<CopyVoiceModels>("copyVoiceModels") {
            model.set(modelDir.flatMap { it.outputDir })
            this.vad.set(vad.flatMap { it.outputFile })
            this.words.set(words.flatMap { it.outputFile })
            outputDir.set(target.layout.buildDirectory.dir("generated/voice-assets"))
        }

        // the flavor's configuration exists once the build script has declared the flavors
        target.configurations.matching { it.name == "${FLAVOR}Implementation" }.configureEach {
            dependencies.add(target.dependencies.create(target.files(sherpa.flatMap { it.outputFile }).builtBy(sherpa)))
        }
        target.extensions.configure<ApplicationExtension> {
            androidResources.noCompress += ".onnx"
        }
        components.onVariants(components.selector().withFlavor("speech" to FLAVOR)) { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(assets, CopyVoiceModels::outputDir)
        }
    }

    /** The model and its tokens, the VAD and the hotwords, as `voice/` in the assets. */
    abstract class CopyVoiceModels : DefaultTask() {
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val model: DirectoryProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val vad: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val words: RegularFileProperty

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun copy() {
            val out = outputDir.get().asFile.resolve("voice")
            out.deleteRecursively()
            out.mkdirs()
            val dir = model.get().asFile.resolve(MODEL_NAME)
            for ((name, asset) in MODEL_FILES) dir.resolve(name).copyTo(out.resolve(asset))
            vad.get().asFile.copyTo(out.resolve("silero_vad.onnx"))
            out.resolve("bpe.vocab").writeText(vocabulary(dir.resolve("bpe.model").readBytes()))
            out.resolve("hotwords.txt").writeText(hotwords(words.get().asFile.readLines()))
        }

        // as VoiceHotwords.spelled: all Chinese, two to twelve characters, a token each
        private fun hotwords(lines: List<String>) = lines.asSequence()
            .filterNot { it.startsWith("#") }
            .map { it.substringBefore('\t') }
            .filter { w -> w.length in 2..12 && w.all { !it.isSurrogate() && Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } }
            .distinct()
            .joinToString("") { it.toList().joinToString(" ") + "\n" }

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
