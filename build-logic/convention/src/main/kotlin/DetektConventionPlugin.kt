/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Applied once, to the root project. Every subproject that turns into an Android or JVM Kotlin
 * module gets detekt, wired to the shared config and its own baseline, so a new module cannot
 * be left out by forgetting a line in some list.
 *
 * Existing findings are frozen in `config/detekt/baseline-<module>.xml`, so `detekt` fails only
 * on new ones. A module with no baseline file yet is held to the full rule set; create it with
 * `./gradlew :<module>:detektBaseline`, run on its own.
 *
 * This configures subprojects from the root, which Gradle's (opt-in, currently off) Isolated
 * Projects mode forbids. If that is ever turned on, move the body into a plugin each module applies.
 */
@Suppress("unused")
class DetektConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        require(target == target.rootProject) { "apply the detekt convention to the root project only" }
        target.subprojects {
            for (id in KOTLIN_MODULE_PLUGINS) {
                pluginManager.withPlugin(id) { configureDetekt() }
            }
        }
    }

    private fun Project.configureDetekt() {
        // a module can match more than one id, configure it once
        if (extensions.findByType(DetektExtension::class.java) != null) return
        pluginManager.apply("dev.detekt")
        extensions.configure(DetektExtension::class.java) {
            buildUponDefaultConfig.set(true)
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            baseline.set(rootProject.file("config/detekt/baseline-${path.removePrefix(":").replace(':', '-')}.xml"))
            parallel.set(true)
        }
    }

    private companion object {
        // AGP's built-in Kotlin means an Android module never applies a kotlin plugin id
        val KOTLIN_MODULE_PLUGINS = listOf(
            "com.android.application",
            "com.android.library",
            "com.android.dynamic-feature",
            "com.android.test",
            "com.android.kotlin.multiplatform.library",
            "org.jetbrains.kotlin.jvm",
            "org.jetbrains.kotlin.multiplatform",
        )
    }
}
