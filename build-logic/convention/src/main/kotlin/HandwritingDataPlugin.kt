/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

/** Handwriting: :lib:handwriting and the `handwriting-models-*` release's files, SHA-256 checked, for the text pack. */
class HandwritingDataPlugin : Plugin<Project> {

    companion object {
        const val EXTRACT_TASK = "extractHandwritingModels"
        private const val RELEASE = "handwriting-models-20261015b"
        private const val URL = "https://github.com/Sraw/youmo-ime/releases/download/$RELEASE/handwriting-models.tar.zst"
        private const val SHA256 = "940e746a2a68822b87aa683e3832541f38e7c36202602dd8445b4b72d26689b9"
        private val FILES = listOf("char_image.ort", "char_path.ort", "chars.txt", "prior.data")
    }

    override fun apply(target: Project) {
        val downloadsDir = target.rootProject.layout.projectDirectory.dir(".gradle/engine-downloads/handwriting")
        val archive = target.tasks.register<DownloadTask>("downloadHandwritingModels") {
            url.set(URL)
            sha256.set(SHA256)
            outputFile.set(downloadsDir.file("$RELEASE.tar.zst"))
        }
        // the archive is handwriting/ and its files: the pack's as they are
        target.tasks.register<ExtractTask>(EXTRACT_TASK) {
            this.archive.set(archive.flatMap { it.outputFile })
            members.set(FILES.map { "handwriting/$it" })
            cmake.set(target.sdkCmake)
            outputDir.set(target.layout.buildDirectory.dir("generated/handwriting-assets"))
        }
        target.dependencies.add("implementation", target.dependencies.project(mapOf("path" to ":lib:handwriting")))
        target.extensions.configure<ApplicationExtension> {
            // sherpa-onnx's: the same file, from the same SHA-256 checked archive (lib/handwriting checks the pins)
            packaging.jniLibs.pickFirsts += "**/libonnxruntime.so"
        }
    }
}
