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
import org.gradle.kotlin.dsl.register

/**
 * What the voice builds (the `voice` flavor of the `speech` dimension) add to the text ones: the
 * speech recognizer and its models, none of them in a text build, which asks for no microphone
 * either (dev/TRAINING-PLAN.md 14).
 *
 * - sherpa-onnx's Android library (k2-fsa, Apache-2.0): the recognizer and onnxruntime, JNI for
 *   each ABI; the ABI splits keep the one an APK is for.
 * - SenseVoice-Small, int8, as sherpa-onnx exports it (FunAudioLLM; FunASR model licence: free to
 *   use and share, crediting the source and keeping the model's name, which the about page does):
 *   Mandarin, Cantonese, English, Japanese, Korean, with punctuation. Not streaming: each stretch
 *   of speech is recognized when it ends.
 * - silero VAD (MIT): where a stretch of speech ends.
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
        // FunAudioLLM's own, not the 2025-09-09 one beside it: that is a Cantonese fine-tune
        // (ASLP-lab WSYue), which hears Mandarin as Cantonese and writes no punctuation
        private const val MODEL_NAME = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
        private const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$MODEL_NAME.tar.bz2"
        private const val MODEL_SHA256 = "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e"
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
        val assets = target.tasks.register<CopyVoiceModels>("copyVoiceModels") {
            model.set(modelDir.flatMap { it.outputDir })
            this.vad.set(vad.flatMap { it.outputFile })
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

    /** The model and its tokens, and the VAD, as `voice/` in the assets. */
    abstract class CopyVoiceModels : DefaultTask() {
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val model: DirectoryProperty

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
            val dir = model.get().asFile.resolve(MODEL_NAME)
            for (name in listOf("model.int8.onnx", "tokens.txt")) dir.resolve(name).copyTo(out.resolve(name))
            vad.get().asFile.copyTo(out.resolve("silero_vad.onnx"))
        }
    }
}
