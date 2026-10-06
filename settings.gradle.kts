pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "fcitx5-android"

include(":lib:ime-core")
include(":lib:ime-eval")
include(":lib:ime-dict-tool")
include(":lib:fcitx5")
include(":lib:fcitx5-lua")
include(":lib:fcitx5-chinese-addons")
include(":lib:sherpa-onnx")
include(":codegen")
include(":app")
