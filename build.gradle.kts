import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin

plugins {
    // the root's `clean` is base's: Kotlin/JS applies base's lifecycle to the root, and one registered here clashed
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
    id("youmo.detekt")
}

// node is fetched from the repository settings.gradle.kts declares, not one Kotlin/JS would add
allprojects {
    for (type in listOf(NodeJsRootPlugin::class.java, NodeJsPlugin::class.java)) {
        plugins.withType(type) { the<NodeJsEnvSpec>().downloadBaseUrl.set(null as String?) }
    }
}

// What a change must pass, for CI and the local full run.
tasks.register("verifyCore") {
    group = "verification"
    description = "Plain JVM modules, the drawing engine (JVM, JS, the theme maker's build), the build plugins, detekt"
    dependsOn(
        ":lib:ime-core:check", ":lib:keyboard-scene:check", ":lib:keyboard-scene:youmoEngine",
        ":lib:ime-dict-tool:test", ":lib:ime-eval:test", ":lib:ime-eval:compileUatTestKotlin",
    )
    // `./gradlew detekt` runs every module's by name; a dependency needs the tasks (DetektConventionPlugin adds them)
    dependsOn(provider { subprojects.mapNotNull { it.tasks.findByName("detekt") } })
    dependsOn(gradle.includedBuild("build-logic").task(":convention:test"))
}
tasks.register("verifyApp") {
    group = "verification"
    description = "The app's JVM tests at SDK 35, lint, and the instrumented tests' sources compiled"
    dependsOn(":app:testDebugUnitTest", ":app:lintDebug", ":app:compileDebugAndroidTestKotlin")
}
tasks.register("verifyApp29") {
    group = "verification"
    description = "The app's JVM tests at SDK 29 (Android 10, minSdk)"
    dependsOn(":app:testDebugUnitTestAt29")
}
tasks.register("verify") {
    group = "verification"
    description = "verifyCore, verifyApp and verifyApp29"
    dependsOn("verifyCore", "verifyApp", "verifyApp29")
}
