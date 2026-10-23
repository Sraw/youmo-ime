/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

/*
 * sherpa-onnx (k2-fsa, Apache-2.0), the speech recognizer of the voice builds, built from source
 * (src/main/cpp: upstream v1.13.8 and our commits on it, see README.md) rather than taken as
 * upstream's AAR: the recognizer is ours to change. Its CMake downloads onnxruntime's Android
 * libraries and its few small dependencies, SHA-256 checked. Only the app's voice flavor depends
 * on it, so a text build never compiles it.
 */
plugins {
    id("org.fcitx.fcitx5.android.lib-convention")
}

android {
    namespace = "com.k2fsa.sherpa.onnx"
    ndkVersion = project.ndkVersion

    defaultConfig {
        ndk {
            abiFilters += project.buildAbiOverride?.split(",") ?: Versions.supportedABIs
        }
        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                // the recognizer, the VAD and the JNI to them: nothing to speak, nothing to run alone
                arguments(
                    "-DBUILD_SHARED_LIBS=ON",
                    // the pinned, SHA-256 checked onnxruntime, not one found on the build machine
                    "-DSHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE=OFF",
                    "-DSHERPA_ONNX_ENABLE_JNI=ON",
                    "-DSHERPA_ONNX_ENABLE_BINARY=OFF",
                    "-DSHERPA_ONNX_ENABLE_CHECK=OFF",
                )
                targets("sherpa-onnx-jni")
            }
        }
    }

    externalNativeBuild {
        cmake {
            version = project.cmakeVersion
            path("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        // a debug build's recognizer optimized too: unoptimized, it takes seconds a sentence
        debug {
            externalNativeBuild {
                cmake {
                    arguments("-DCMAKE_BUILD_TYPE=Release")
                }
            }
        }
    }

    sourceSets {
        getByName("main") {
            // upstream's Kotlin API, where upstream keeps it
            kotlin.directories += "src/main/cpp/sherpa-onnx/kotlin-api"
        }
    }
}
