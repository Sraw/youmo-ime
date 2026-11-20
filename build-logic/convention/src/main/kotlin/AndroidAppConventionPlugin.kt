/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.internal.tasks.CompileArtProfileTask
import com.android.build.gradle.internal.tasks.ExpandArtProfileWildcardsTask
import com.android.build.gradle.internal.tasks.MergeArtProfileTask
import com.android.build.gradle.tasks.PackageApplication
import com.mikepenz.aboutlibraries.plugin.AboutLibrariesExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.internal.provider.AbstractProperty
import org.gradle.api.internal.provider.Providers
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The prototype of an Android Application
 *
 * - Provide default configuration for `android {...}`
 * - Add desugar JDK libs
 * - Fail the build if the merged manifest asks for a permission not on the list ([PermissionCheckTask])
 */
@Suppress("unused")
class AndroidAppConventionPlugin : AndroidBaseConventionPlugin() {

    override fun apply(target: Project) {
        target.pluginManager.apply(target.libs.plugins.android.application.get().pluginId)

        super.apply(target)

        target.extensions.configure<ApplicationExtension> {
            defaultConfig {
                targetSdk = Versions.targetSdk
                versionCode = Versions.calculateVersionCode()
                versionName = Versions.checkedVersionName(target.buildVersionName)
            }
            buildTypes {
                release {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    signingConfig = signingConfigs.fromProjectEnv(target)
                    proguardFile(getDefaultProguardFile("proguard-android-optimize.txt"))
                }
                debug {
                    applicationIdSuffix = ".debug"
                }
                all {
                    // remove META-INF/version-control-info.textproto
                    @Suppress("UnstableApiUsage")
                    vcsInfo.include = false
                }
            }
            compileOptions {
                isCoreLibraryDesugaringEnabled = true
            }
        }

        target.extensions.configure<ApplicationExtension> {
            dependenciesInfo {
                includeInApk = false
                includeInBundle = false
            }
            packaging {
                resources {
                    excludes += setOf(
                        "/META-INF/*.version",
                        "/META-INF/androidx/**",
                        "/DebugProbesKt.bin",
                        "/kotlin-tooling-metadata.json"
                    )
                }
            }
        }

        // through Gradle's internals, so a Gradle or AGP that changed them fails here instead of shipping the file
        target.tasks.withType<PackageApplication> {
            val valueField = checkNotNull(AbstractProperty::class.java.declaredFields.find { it.name == "value" }) {
                "AbstractProperty has no field `value` (a Gradle upgrade?): app-metadata.properties would go into the APK"
            }
            valueField.isAccessible = true
            doFirst {
                valueField.set(appMetadata, Providers.notDefined<RegularFile>())
                allInputFilesWithNameOnlyPathSensitivity.removeAll { true }
            }
        }

        // remove assets/dexopt/baseline.prof{,m}: for an APK installed from a file ART's dexopt finds the hot methods anyway
        target.tasks.withType<MergeArtProfileTask> { enabled = false }
        target.tasks.withType<ExpandArtProfileWildcardsTask> { enabled = false }
        target.tasks.withType<CompileArtProfileTask> { enabled = false }

        target.extensions.configure<ApplicationAndroidComponentsExtension> {
            // Check the merged manifest in its own path, so no APK or bundle is built without it
            onVariants { v ->
                val permissionCheck = target.tasks.register<PermissionCheckTask>("checkPermissions${v.name.capitalized()}") {
                    allowed.set(PermissionCheckTask.ALLOWED)
                }
                v.artifacts.use(permissionCheck)
                    .wiredWithFiles(PermissionCheckTask::mergedManifest, PermissionCheckTask::checkedManifest)
                    .toTransform(SingleArtifact.MERGED_MANIFEST)
            }
            finalizeDsl {
                // applicationId is not set upon apply
                it.defaultConfig {
                    // https://www.norio.be/blog/archivesBaseName-removed-from-gradle9.html
                    target.extensions.configure<BasePluginExtension> {
                        archivesName.set("$applicationId-$versionName")
                    }
                }
            }
        }

        target.pluginManager.apply(target.libs.plugins.aboutlibraries.get().pluginId)

        target.configure<AboutLibrariesExtension> {
            collect {
                configPath.set(target.file("licenses").takeIf { it.exists() })
                fetchRemoteLicense.set(false)
                fetchRemoteFunding.set(false)
                includePlatform.set(false)
            }
            export {
                excludeFields.set(
                    setOf("generated", "developers", "organization", "scm", "funding", "content")
                )
            }
        }

        target.dependencies.add("coreLibraryDesugaring", target.libs.android.desugarJDKLibs)
    }

    /** Fails the build if the merged manifest asks for a permission not in [allowed]: a dependency could merge one in unseen. */
    abstract class PermissionCheckTask : DefaultTask() {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val mergedManifest: RegularFileProperty

        /** What the build may ask for: [ALLOWED]. */
        @get:Input
        abstract val allowed: SetProperty<String>

        @get:OutputFile
        abstract val checkedManifest: RegularFileProperty

        @TaskAction
        fun execute() {
            val manifest = mergedManifest.get().asFile
            val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(manifest).documentElement
            val permitted = allowed.get()
            val found = PERMISSION_TAGS.flatMap { tag ->
                val nodes = root.getElementsByTagName(tag)
                (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(ANDROID_NS, "name") }
            }.filter { it !in permitted }
            check(found.isEmpty()) {
                "The merged manifest asks for ${found.joinToString()}, which this build does not ask for (PRIVACY.md): " +
                    "build/outputs/logs/manifest-merger-*-report.txt names the dependency that adds it"
            }
            manifest.copyTo(checkedManifest.get().asFile, overwrite = true)
        }

        companion object {
            private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
            private val PERMISSION_TAGS = listOf("uses-permission", "uses-permission-sdk-23")

            /** Key feedback and the microphone (asked for at voice's first use); nothing else, the network above all. */
            val ALLOWED = setOf("android.permission.VIBRATE", "android.permission.RECORD_AUDIO")
        }
    }

}
