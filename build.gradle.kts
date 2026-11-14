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
    id("youmo.detekt")
}

// node is fetched from the repository settings.gradle.kts declares (FAIL_ON_PROJECT_REPOS), not one
// Kotlin/JS would add to the project (the root's setup and each JS module's)
allprojects {
    for (type in listOf(NodeJsRootPlugin::class.java, NodeJsPlugin::class.java)) {
        plugins.withType(type) { the<NodeJsEnvSpec>().downloadBaseUrl.set(null as String?) }
    }
}

// The one list of what a change must pass, for CI (unit_test.yml, a job each, side by side) and the local full run
// (dev/review/kit/scripts/verify.sh, all three at once). Not in either: the APK (pull_request.yml builds the
// release one; locally verify.sh adds :app:assembleDebug for the emulator) and :lib:ime-eval:uatTest (the app's data).
// youmoEngine is here because the theme maker's node tests read its output, which no `check` makes.
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
