/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

/** Applied to the root: every Kotlin subproject gets detekt with the shared config and its own baseline. */
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
            // every source set's code, not only main and test
            source.setFrom(kotlinSourceDirs(projectDir))
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

/** `src/<source set>/java` and `src/<source set>/kotlin` under [projectDir], those there are. */
internal fun kotlinSourceDirs(projectDir: File): List<File> =
    projectDir.resolve("src").listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }
        .flatMap { set -> listOf(set.resolve("java"), set.resolve("kotlin")) }
        .filter { it.isDirectory }
