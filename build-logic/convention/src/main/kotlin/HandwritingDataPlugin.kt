/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * Handwriting, in both builds: the recognizers' runtime (:lib:handwriting, onnxruntime and a JNI to
 * it) and the release `handwriting-models-*` of this fork (its manifest.txt says what each file
 * is and how it was made). Of its files the single character's are taken: two ORT-format models
 * (its image, its path), the characters they tell apart, and their frequencies. Left out: a line's
 * and an overlay's models, as the app writes a character at a time, and the character bigram, as
 * the characters are read as a sentence with the pinyin model. Downloaded once, SHA-256 checked,
 * into the assets under `handwriting/`, uncompressed: the models are mapped where they lie.
 */
class HandwritingDataPlugin : Plugin<Project> {

    companion object {
        private const val RELEASE = "handwriting-models-20261015"
        private const val URL = "https://github.com/Sraw/youmo-ime/releases/download/$RELEASE/handwriting-models.tar.zst"
        private const val SHA256 = "8e2c7d4afc9be4c834a0d912dda0af0b8a1034abc88b184ce967decc14d756ca"
        private val FILES = listOf("char_image.ort", "char_path.ort", "chars.txt", "prior.data")
    }

    override fun apply(target: Project) {
        val downloadsDir = target.rootProject.layout.projectDirectory.dir(".gradle/engine-downloads/handwriting")
        val archive = target.tasks.register<DownloadTask>("downloadHandwritingModels") {
            url.set(URL)
            sha256.set(SHA256)
            outputFile.set(downloadsDir.file("$RELEASE.tar.zst"))
        }
        // the archive is handwriting/ and its files: the assets as they are
        val assets = target.tasks.register<ExtractTask>("extractHandwritingModels") {
            this.archive.set(archive.flatMap { it.outputFile })
            members.set(FILES.map { "handwriting/$it" })
            cmake.set(target.sdkCmake)
            outputDir.set(target.layout.buildDirectory.dir("generated/handwriting-assets"))
        }
        target.dependencies.add("implementation", target.dependencies.project(mapOf("path" to ":lib:handwriting")))
        target.extensions.configure<ApplicationExtension> {
            androidResources.noCompress += listOf(".ort", ".data")
            // sherpa-onnx's in a voice build: the same file, from the same SHA-256 checked archive (lib/handwriting checks the pins)
            packaging.jniLibs.pickFirsts += "**/libonnxruntime.so"
        }
        val components = target.extensions.getByType<ApplicationAndroidComponentsExtension>()
        components.onVariants { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(assets, ExtractTask::outputDir)
        }
    }
}
