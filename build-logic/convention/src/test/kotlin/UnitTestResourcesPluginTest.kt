/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

import org.gradle.kotlin.dsl.register
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** What Robolectric is pointed at: the app's linked resources, with the tests' own assets and no others. */
class UnitTestResourcesPluginTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(file: File, vararg entries: Pair<String, String>) = file.also {
        ZipOutputStream(it.outputStream()).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
    }

    private fun entries(file: File) = ZipFile(file).use { z ->
        z.entries().toList().associate { it.name to z.getInputStream(it).readBytes().decodeToString() }
    }

    @Test
    fun theResourcesAreKeptAndTheTestsAssetsAdded() {
        val apk = zip(tmp.newFile("r.ap_"), "AndroidManifest.xml" to "m", "resources.arsc" to "a", "res/x.xml" to "x", "assets/old" to "o")
        val assets = tmp.newFolder("assets").also { File(it, "engine").mkdirs(); File(it, "engine/emoji.data").writeText("e") }
        val out = tmp.newFile("out.ap_")
        UnitTestResourcesTask.copyWithAssets(apk, assets, out)
        assertEquals(
            mapOf("AndroidManifest.xml" to "m", "resources.arsc" to "a", "res/x.xml" to "x", "assets/engine/emoji.data" to "e"),
            entries(out),
        )
    }

    @Test
    fun noAssetsFolderLeavesNoAssets() {
        val apk = zip(tmp.newFile("r.ap_"), "resources.arsc" to "a", "assets/old" to "o")
        val out = tmp.newFile("out.ap_")
        UnitTestResourcesTask.copyWithAssets(apk, null, out)
        assertEquals(mapOf("resources.arsc" to "a"), entries(out))
    }

    @Test
    fun theConfigPointsAtTheCopyTheManifestAndThePackage() {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder("p")).build()
        val linked = tmp.newFolder("linked").also { zip(File(it, "linked-resources-textX86_64Debug.ap_"), "resources.arsc" to "a") }
        val manifests = tmp.newFolder("manifests").also { File(it, "x86_64").mkdirs(); File(it, "x86_64/AndroidManifest.xml").writeText("<manifest/>") }
        val task = project.tasks.register<UnitTestResourcesTask>("write").get().apply {
            linkedResources.set(linked)
            this.manifests.set(manifests)
            customPackage.set("org.example")
            outputDir.set(tmp.newFolder("out"))
        }
        task.write()
        val config = Properties().apply {
            File(task.outputDir.get().asFile, "com/android/tools/test_config.properties").reader().use(::load)
        }
        val copy = File(config.getProperty("android_resource_apk"))
        assertTrue(copy.isFile && copy.parentFile == task.outputDir.get().asFile)
        assertEquals(mapOf("resources.arsc" to "a"), entries(copy))
        assertEquals(File(manifests, "x86_64/AndroidManifest.xml").absolutePath, config.getProperty("android_merged_manifest"))
        assertEquals("org.example", config.getProperty("android_custom_package"))
    }

    // F3789: a build of every ABI links one .ap_ and merges one manifest per split; write() took
    // them with `single`, which threw there. Any pair will do, but the same one every time
    @Test
    fun severalAbiSplitsWriteTheSamePairEveryTime() {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder("p")).build()
        val linked = tmp.newFolder("linked").also { dir ->
            for (abi in listOf("X86_64", "Arm64-v8a")) zip(File(dir, "linked-resources-text${abi}Debug.ap_"), "resources.arsc" to abi)
        }
        val manifests = tmp.newFolder("manifests").also { dir ->
            for (abi in listOf("x86_64", "arm64-v8a")) File(dir, abi).mkdirs().also { File(dir, "$abi/AndroidManifest.xml").writeText("<manifest/>") }
        }
        val task = project.tasks.register<UnitTestResourcesTask>("write").get().apply {
            linkedResources.set(linked)
            this.manifests.set(manifests)
            customPackage.set("org.example")
            outputDir.set(tmp.newFolder("out"))
        }
        task.write()
        val config = Properties().apply {
            File(task.outputDir.get().asFile, "com/android/tools/test_config.properties").reader().use(::load)
        }
        assertEquals(mapOf("resources.arsc" to "Arm64-v8a"), entries(File(config.getProperty("android_resource_apk"))))
        assertEquals(File(manifests, "arm64-v8a/AndroidManifest.xml").absolutePath, config.getProperty("android_merged_manifest"))
    }
}
