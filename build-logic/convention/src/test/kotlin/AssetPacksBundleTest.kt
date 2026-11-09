/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * What a release uploads: the two bundles users download, and the listing the next release
 * compares with. A mistake here would show only on the day of a release.
 */
class AssetPacksBundleTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun listing(id: String, version: Int = 1, content: String = "c-$id", releaseKey: Boolean = true): AssetPacksPlugin.Listing {
        val file = folder.root.resolve("packs/youmo-$id-v$version.youmopack").apply { parentFile.mkdirs(); writeText(id) }
        return AssetPacksPlugin.Listing(id, version, 1, file, "s-$id", content, releaseKey)
    }

    private fun apk() = folder.root.resolve("app-release.apk").apply { writeText("apk") }

    private fun names(zip: File) = ZipFile(zip).use { z -> z.entries().toList().map { it.name } }

    @Test
    fun theTextBundleHasTheTextPackAndTheVoiceBundleBoth() {
        val out = folder.root.resolve("out")
        AssetPacksPlugin.writeBundles(apk(), listing("text"), listing("voice"), "0.6.0", emptyMap(), out)
        assertEquals(listOf("安装说明.txt", "youmo-0.6.0.apk", "youmo-text-v1.youmopack"), names(out.resolve("youmo-0.6.0-text.zip")))
        assertEquals(
            listOf("安装说明.txt", "youmo-0.6.0.apk", "youmo-text-v1.youmopack", "youmo-voice-v1.youmopack"),
            names(out.resolve("youmo-0.6.0-voice.zip")),
        )
        assertEquals(
            setOf("youmo-0.6.0.apk", "youmo-0.6.0-text.zip", "youmo-0.6.0-voice.zip", "youmo-text-v1.youmopack", "youmo-voice-v1.youmopack", "release-packs.json"),
            out.list()!!.toSet(),
        )
    }

    @Test
    fun theReleasesListingIsReadBackAsTheNextOnesPrevious() {
        val out = folder.root.resolve("out")
        val before = mapOf("text" to listing("text", content = "c-text"))
        AssetPacksPlugin.writeBundles(apk(), listing("text"), listing("voice", version = 2), "0.6.0", before, out)
        val read = AssetPacksPlugin.Listing.readAll(out.resolve("release-packs.json"))
        assertEquals(setOf("text", "voice"), read.keys)
        assertEquals(2, read.getValue("voice").version)
        assertEquals("c-voice", read.getValue("voice").contentSha256)
        val text = out.resolve("release-packs.json").readText()
        // the text pack as the last release had it: not uploaded again; the voice pack new
        assertTrue(text, "\"pack\": \"text\"" in text && text.substringAfter("\"pack\": \"text\"").substringBefore("}").contains("\"changed\": false"))
        assertTrue(text, text.substringAfter("\"pack\": \"voice\"").substringBefore("}").contains("\"changed\": true"))
    }

    @Test
    fun aTestKeyPackOrAChangeUnderTheSameVersionWritesNothing() {
        val out = folder.root.resolve("out")
        assertThrows(IllegalStateException::class.java) {
            AssetPacksPlugin.writeBundles(apk(), listing("text", releaseKey = false), listing("voice"), "0.6.0", emptyMap(), out)
        }
        assertFalse(out.exists())
        val before = mapOf("text" to listing("text", content = "old"))
        assertThrows(GradleException::class.java) {
            AssetPacksPlugin.writeBundles(apk(), listing("text", content = "new"), listing("voice"), "0.6.0", before, out)
        }
        assertFalse(out.exists())
    }

    @Test
    fun jsonStringsEscapeQuotesBackslashesAndControlCharacters() {
        assertEquals("\"a\\\"b\\\\c\\u000ad\\u0009\"", AssetPacksPlugin.jsonString("a\"b\\c\nd\t"))
        assertEquals("\"文字\"", AssetPacksPlugin.jsonString("文字"))
    }

    // the build's list of which pack owns which asset path is the app's PackKind's, repeated: one side
    // changed alone would show only when a device imports the pack
    @Test
    fun theOwnedPathsAreTheAppsPackKinds() {
        val source = File(System.getProperty("user.dir"))
            .resolve("../../lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/pack/PackManifest.kt").canonicalFile.readText()
        val kinds = Regex("""(\w+)\(\s*"(\w+)",\s*listOf\(([^)]*)\)""").findAll(source).associate { m ->
            m.groupValues[2] to Regex("\"([^\"]+)\"").findAll(m.groupValues[3]).map { it.groupValues[1] }.toList()
        }
        assertEquals(AssetPacksPlugin.OWNED, kinds)
    }
}
