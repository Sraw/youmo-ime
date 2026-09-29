plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.aboutlibraries) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.detekt) apply false
    // applies detekt to every Android/JVM Kotlin module, see DetektConventionPlugin
    id("org.fcitx.fcitx5.android.detekt")
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
