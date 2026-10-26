/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.FilterConfiguration.FilterType
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * An app that packs the native libraries of its library modules (handwriting, sherpa-onnx) and has
 * none of its own to build: an APK per ABI, each with its own version code.
 */
@Suppress("unused")
class NativeAppConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply(target.libs.plugins.android.application.get().pluginId)

        // split apks should be disabled when building bundle
        // https://issuetracker.google.com/issues/402800800
        val isBuildingBundle = target.rootProject.gradle.startParameter.taskNames.any {
            it.startsWith("${target.path}:bundle")
        }
        target.extensions.configure<ApplicationExtension> {
            ndkVersion = target.ndkVersion
            if (!isBuildingBundle) {
                splits.abi {
                    isEnable = true
                    isUniversalApk = false
                    reset()
                    (target.buildAbiOverride?.split(",") ?: Versions.supportedABIs).forEach {
                        include(it)
                    }
                }
            }
            packaging {
                jniLibs {
                    useLegacyPackaging = true
                }
            }
        }

        target.extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants { variant ->
                // different version code based on abi
                variant.outputs.forEach { output ->
                    val abi = output.filters.find { it.filterType == FilterType.ABI }
                    if (abi != null) {
                        output.versionCode.set(Versions.calculateVersionCode(abi.identifier))
                    }
                }
            }
        }
    }

}
