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
        // node for lib/keyboard-scene's JS tests here, as FAIL_ON_PROJECT_REPOS forbids Kotlin/JS adding it to the root
        exclusiveContent {
            forRepository {
                ivy("https://nodejs.org/dist") {
                    name = "Node.js"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("org.nodejs", "node") }
        }
    }
}

rootProject.name = "youmo-ime"

include(":lib:ime-core")
include(":lib:keyboard-scene")
include(":lib:ime-eval")
include(":lib:ime-dict-tool")
include(":lib:sherpa-onnx")
include(":lib:handwriting")
include(":codegen")
include(":app")
include(":testbed")
