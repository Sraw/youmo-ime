/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

import com.android.build.gradle.tasks.ExternalNativeBuildJsonTask

/*
 * The handwriting recognizers' runtime: onnxruntime (MIT) and a small JNI to it (OrtModel). The
 * onnxruntime is the build sherpa-onnx links in the voice builds, the same file (csukuangfj's
 * onnxruntime-libs v1.28.2, SHA-256 checked), so a voice build packs one copy of it
 * (pickFirst in the app). Its ORT-format models are mapped from the APK, not read onto the heap.
 */
plugins {
    id("org.fcitx.fcitx5.android.lib-convention")
}

val ortVersion = "1.28.2"
val ortSha256 = "01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d"

// sherpa-onnx's CMake pins the archive on its own; the app keeps whichever copy comes first, so the
// two pins are held equal here: one bumped alone would ship a library the other was not built for
for (abi in listOf("arm", "aarch64", "x86_64", "i686")) {
    val pin = rootProject.file("lib/sherpa-onnx/src/main/cpp/cmake/onnxruntime-android-$abi.cmake")
    check(pin.readText().contains("SHA256=$ortSha256")) { "onnxruntime: $pin pins another archive than lib/handwriting" }
}

val downloadOnnxruntime = tasks.register<DownloadTask>("downloadOnnxruntime") {
    url.set("https://github.com/csukuangfj/onnxruntime-libs/releases/download/v$ortVersion/onnxruntime-android-$ortVersion.zip")
    sha256.set(ortSha256)
    outputFile.set(rootProject.layout.projectDirectory.file(".gradle/engine-downloads/onnxruntime-android-$ortVersion.zip"))
}

val onnxruntimeDir = layout.buildDirectory.dir("onnxruntime")

// the headers and the libraries, of the archive's AAR layout; not its Java API
val unpackOnnxruntime = tasks.register<Sync>("unpackOnnxruntime") {
    from(downloadOnnxruntime.flatMap { it.outputFile }.map { zipTree(it) }) {
        include("headers/**", "jni/*/libonnxruntime.so")
    }
    into(onnxruntimeDir)
}

android {
    namespace = "org.fcitx.fcitx5.android.handwriting"
    ndkVersion = project.ndkVersion

    defaultConfig {
        ndk {
            abiFilters += project.buildAbiOverride?.split(",") ?: Versions.supportedABIs
        }
        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DORT_DIR=${onnxruntimeDir.get().asFile.absolutePath}",
                )
                targets("youmo-handwriting")
            }
        }
    }

    externalNativeBuild {
        cmake {
            version = project.cmakeVersion
            path("src/main/cpp/CMakeLists.txt")
        }
    }
}

tasks.withType<ExternalNativeBuildJsonTask>().configureEach {
    dependsOn(unpackOnnxruntime)
}
