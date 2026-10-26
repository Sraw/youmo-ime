/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.tasks.PackageAndroidArtifact
import kotlinx.serialization.Serializable
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault

/**
 * Add task `generateBuildMetadata${Variant}`
 */
@Suppress("unused")
class BuildMetadataPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        // read once: BuildConfig and build-metadata.json name the same build
        val version = target.buildVersionName
        val commit = target.buildCommitHash
        val time = target.buildTimestamp
        target.extensions.configure<ApplicationExtension> {
            buildFeatures {
                buildConfig = true
            }
            defaultConfig {
                buildConfigField("String", "BUILD_GIT_HASH", "\"$commit\"")
                buildConfigField("long", "BUILD_TIME", time)
            }
        }
        target.extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants { variant ->
                val variantName = variant.name.capitalized()
                target.afterEvaluate {
                    target.tasks.register<BuildMetadataTask>("generateBuildMetadata${variantName}") {
                        val packageTask =
                            target.tasks.getByName("package${variantName}") as PackageAndroidArtifact
                        // create metadata file after package, because it's outputDirectory would
                        // be cleared at some time before package
                        mustRunAfter(packageTask)
                        versionName.set(version)
                        commitHash.set(commit)
                        timestamp.set(time)
                        outputFile.set(packageTask.outputDirectory.file("build-metadata.json"))
                    }.also {
                        target.tasks.getByName("assemble${variantName}").dependsOn(it)
                    }
                }
            }
        }
    }

    @DisableCachingByDefault(because = "writing three strings is quicker than the cache would be")
    abstract class BuildMetadataTask : DefaultTask() {
        @Serializable
        data class BuildMetadata(
            val versionName: String,
            val commitHash: String,
            val timestamp: String
        )

        @get:Input
        abstract val versionName: Property<String>

        @get:Input
        abstract val commitHash: Property<String>

        @get:Input
        abstract val timestamp: Property<String>

        @get:OutputFile
        abstract val outputFile: RegularFileProperty

        @TaskAction
        fun execute() {
            val metadata = BuildMetadata(versionName.get(), commitHash.get(), timestamp.get())
            outputFile.get().asFile.writeText(json.encodeToString(metadata))
        }
    }
}