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
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The prototype of an Android Application
 *
 * - Configure dependency for [DataDescriptorPlugin] task (If have)
 * - Provide default configuration for `android {...}`
 * - Add desugar JDK libs
 * - Fail the build if the merged manifest asks for a network permission ([NoNetworkPermissionTask])
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
                versionName = target.buildVersionName
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
                        "/META-INF/*.kotlin_module",  // cannot be excluded actually
                        "/META-INF/androidx/**",
                        "/DebugProbesKt.bin",
                        "/kotlin-tooling-metadata.json"
                    )
                }
            }
        }

        // remove META-INF/com/android/build/gradle/app-metadata.properties
        target.tasks.withType<PackageApplication> {
            val valueField =
                AbstractProperty::class.java.declaredFields.find { it.name == "value" } ?: run {
                    println("class AbstractProperty field value not found, something could have gone wrong")
                    return@withType
                }
            valueField.isAccessible = true
            doFirst {
                valueField.set(appMetadata, Providers.notDefined<RegularFile>())
                allInputFilesWithNameOnlyPathSensitivity.removeAll { true }
            }
        }

        // try to remove <pkg_name>-<version_name>.kotlin_module, but it does not work ¯\_(ツ)_/¯
        target.tasks.withType<KotlinCompile> {
            doLast f@{
                val ktClass = outputs.files.files.filter { it.path.contains("kotlin-classes") }
                if (ktClass.isEmpty()) return@f
                val metaInf = ktClass.first().resolve("META-INF")
                if (!metaInf.exists() || !metaInf.isDirectory) return@f
                metaInf.listFiles()?.forEach {
                    if (it.name.endsWith(".kotlin_module")) {
                        it.delete()
                    }
                }
            }
        }

        // remove assets/dexopt/baseline.prof{,m} (baseline profile)
        target.tasks.withType<MergeArtProfileTask> { enabled = false }
        target.tasks.withType<ExpandArtProfileWildcardsTask> { enabled = false }
        target.tasks.withType<CompileArtProfileTask> { enabled = false }

        target.extensions.configure<ApplicationAndroidComponentsExtension> {
            // Add dependency relationships for data descriptor task
            onVariants { v ->
                val variantName = v.name.capitalized()
                // Evaluation should be delayed as we need be able to see other tasks
                target.afterEvaluate {
                    tasks.findByName(DataDescriptorPlugin.TASK)?.also {
                        tasks.findByName("merge${variantName}Assets")?.dependsOn(it)
                        tasks.findByName("lintVitalAnalyze${variantName}")?.dependsOn(it)
                        tasks.findByName("generate${variantName}LintVitalReportModel")?.dependsOn(it)
                    }
                }
            }
            // Check the merged manifest in its own path, so no APK or bundle is built without it
            onVariants { v ->
                val permissionCheck = target.tasks.register(
                    "checkNoNetworkPermission${v.name.capitalized()}", NoNetworkPermissionTask::class.java
                )
                v.artifacts.use(permissionCheck)
                    .wiredWithFiles(NoNetworkPermissionTask::mergedManifest, NoNetworkPermissionTask::checkedManifest)
                    .toTransform(SingleArtifact.MERGED_MANIFEST)
            }
            // Make data descriptor depend on fcitx component if have
            // Since we are using finalizeDsl, there is no need to do afterEvaluate
            finalizeDsl {
                target.tasks.findByName(DataDescriptorPlugin.TASK)?.also { dataDescriptorTask ->
                    FcitxComponentPlugin.DEPENDENT_TASKS
                        .mapNotNull { taskName -> target.tasks.findByName(taskName) }
                        .forEach { componentTask -> dataDescriptorTask.dependsOn(componentTask) }
                }
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

    /**
     * Fails the build if the merged manifest asks for a network permission, and otherwise passes it
     * on unchanged. The app promises to have none (README, PRIVACY.md), and a dependency's manifest
     * would merge one in without a line of this repository changing.
     */
    abstract class NoNetworkPermissionTask : DefaultTask() {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val mergedManifest: RegularFileProperty

        @get:OutputFile
        abstract val checkedManifest: RegularFileProperty

        @TaskAction
        fun execute() {
            val manifest = mergedManifest.get().asFile
            val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(manifest).documentElement
            val found = PERMISSION_TAGS.flatMap { tag ->
                val nodes = root.getElementsByTagName(tag)
                (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(ANDROID_NS, "name") }
            }.filter { it in NETWORK_PERMISSIONS }
            check(found.isEmpty()) {
                "The merged manifest asks for ${found.joinToString()}, but the app has no network permission " +
                    "(PRIVACY.md): build/outputs/logs/manifest-merger-*-report.txt names the dependency that adds it"
            }
            manifest.copyTo(checkedManifest.get().asFile, overwrite = true)
        }

        companion object {
            private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
            private val PERMISSION_TAGS = listOf("uses-permission", "uses-permission-sdk-23")
            private val NETWORK_PERMISSIONS =
                setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")
        }
    }

}
