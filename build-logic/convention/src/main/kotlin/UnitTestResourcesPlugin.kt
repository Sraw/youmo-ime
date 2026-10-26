/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.HostTestBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Gives Robolectric the app's resources without its assets. `isIncludeAndroidResources = true`
 * would make the unit tests depend on merged assets, which drag in the engine data; the linked
 * resources (res + manifest, no assets) that the R class already needs are enough. This writes
 * the `test_config.properties` AGP would, pointing at them, onto the unit tests' classpath.
 */
@Suppress("unused")
class UnitTestResourcesPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants { variant ->
                val unitTest = variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE] ?: return@onVariants
                val name = variant.name
                val cap = name.capitalized()
                val build = target.layout.buildDirectory
                val task = target.tasks.register<UnitTestResourcesTask>("write${cap}UnitTestResourceConfig") {
                    dependsOn("process${cap}Resources", "process${cap}Manifest")
                    linkedResources.set(build.dir("intermediates/linked_resources_binary_format/$name/process${cap}Resources"))
                    manifests.set(build.dir("intermediates/merged_manifests/$name/process${cap}Manifest"))
                    customPackage.set(variant.namespace)
                    // the tests' own small assets (an emoji.data of a few), not the app's built ones
                    val testAssets = target.layout.projectDirectory.dir("src/test/assets")
                    if (testAssets.asFile.isDirectory) assets.set(testAssets)
                }
                unitTest.sources.resources?.addGeneratedSourceDirectory(task, UnitTestResourcesTask::outputDir)
            }
        }
    }
}

@DisableCachingByDefault(because = "writes absolute paths")
abstract class UnitTestResourcesTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val linkedResources: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val manifests: DirectoryProperty

    @get:Input
    abstract val customPackage: Property<String>

    // Robolectric reads assets only from the resource APK (android_merged_assets is ignored with
    // binary resources): these are copied into a copy of it
    // optional: the plugin sets it only when the tests have an assets folder
    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val assets: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        // one .ap_ and one manifest per ABI split when several ABIs are built (CI builds all four);
        // they differ only in the version code, which Robolectric does not read: any one will do
        val apk = firstOf(linkedResources.asFileTree.files) { it.name.endsWith(".ap_") }
        val manifest = firstOf(manifests.asFileTree.files) { it.name == "AndroidManifest.xml" }
        val withAssets = outputDir.file("test-resources.ap_").get().asFile
        withAssets.parentFile.mkdirs()
        copyWithAssets(apk, assets.orNull?.asFile, withAssets)
        val file = outputDir.file("com/android/tools/test_config.properties").get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            "android_resource_apk=${withAssets.absolutePath}\n" +
                "android_merged_manifest=${manifest.absolutePath}\n" +
                "android_custom_package=${customPackage.get()}\n"
        )
    }

    companion object {
        /** The first by path of [files] matching [match], so the pick is the same every build. */
        fun firstOf(files: Set<File>, match: (File) -> Boolean): File =
            files.filter(match).minByOrNull { it.path } ?: error("none in $files")

        /** [apk]'s entries, then every file under [assets] as `assets/<its path>`, into [to]. */
        fun copyWithAssets(apk: File, assets: File?, to: File) {
            ZipOutputStream(to.outputStream().buffered()).use { out ->
                ZipFile(apk).use { zip ->
                    for (e in zip.entries()) {
                        if (e.name.startsWith("assets/")) continue
                        out.putNextEntry(ZipEntry(e.name))
                        zip.getInputStream(e).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
                assets?.walkTopDown()?.filter { it.isFile }?.sortedBy { it.path }?.forEach { f ->
                    out.putNextEntry(ZipEntry("assets/" + f.relativeTo(assets).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
    }
}
