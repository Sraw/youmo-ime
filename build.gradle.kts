import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin

plugins {
    // the root's `clean` (deletes its build directory): base's, not one of its own, since Kotlin/JS
    // (lib/keyboard-scene) applies base's lifecycle to the root for its node and npm setup, and a
    // `clean` registered here then clashed with it
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.aboutlibraries) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.detekt) apply false
    // applies detekt to every Android/JVM Kotlin module, see DetektConventionPlugin
    id("org.fcitx.fcitx5.android.detekt")
}

// node is fetched from the repository settings.gradle.kts declares (FAIL_ON_PROJECT_REPOS), not one
// Kotlin/JS would add to the project (the root's setup and each JS module's)
allprojects {
    for (type in listOf(NodeJsRootPlugin::class.java, NodeJsPlugin::class.java)) {
        plugins.withType(type) { the<NodeJsEnvSpec>().downloadBaseUrl.set(null as String?) }
    }
}
