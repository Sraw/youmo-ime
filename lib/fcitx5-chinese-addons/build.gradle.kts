plugins {
    id("org.fcitx.fcitx5.android.lib-convention")
    id("org.fcitx.fcitx5.android.native-lib-convention")
    id("org.fcitx.fcitx5.android.fcitx-headers")
}

android {
    namespace = "org.fcitx.fcitx5.android.lib.fcitx5_chinese_addons"

    defaultConfig {
        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                targets(
                    // dummy "cmake" target
                    "cmake",
                    // fcitx5-chinese-addons
                    "scel2org5",
                    "chttrans",
                    "fullwidth",
                    "punctuation",
                )
            }
        }
    }

    prefab {
        create("cmake") {
            headerOnly = true
            headers = "src/main/cpp/cmake"
        }
        create("scel2org5") {
            libraryName = "libscel2org5"
            // no headers
        }
        val moduleHeadersPrefix = "build/headers/usr/include/Fcitx5/Module/fcitx-module"
        create("chttrans") {
            libraryName = "libchttrans"
            // no headers
        }
        create("fullwidth") {
            libraryName = "libfullwidth"
            // no headers
        }
        create("punctuation") {
            libraryName = "libpunctuation"
            headers = "$moduleHeadersPrefix/punctuation"
        }
    }
}

dependencies {
    implementation(project(":lib:fcitx5"))
    implementation(project(":lib:fcitx5-lua"))
}
