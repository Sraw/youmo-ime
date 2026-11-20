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

/** An app packing its library modules' native libraries: an APK per ABI, each with its own version code. */
@Suppress("unused")
class NativeAppConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply(target.libs.plugins.android.application.get().pluginId)

        target.extensions.configure<ApplicationExtension> {
            ndkVersion = target.ndkVersion
            // one APK an ABI; no app bundle is made (it would need the splits off)
            splits.abi {
                isEnable = true
                isUniversalApk = false
                reset()
                Versions.buildABIs(target.buildAbiOverride).forEach {
                    include(it)
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
