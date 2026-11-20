/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/** What a release uploads: a mistake here would show only on the day of a release. */
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
        val json = groovy.json.JsonSlurper().parseText(out.resolve("release-packs.json").readText()) as Map<*, *>
        val changed = (json["packs"] as List<*>).associate { (it as Map<*, *>)["pack"] to it["changed"] }
        // the text pack as the last release had it: not uploaded again; the voice pack new
        assertEquals(mapOf("text" to false, "voice" to true), changed)
    }

    @Test
    fun aRefusedReleaseWritesNothing() {
        fun write(apkFile: File = apk(), text: AssetPacksPlugin.Listing = listing("text"), voice: AssetPacksPlugin.Listing = listing("voice"), version: String = "0.6.0",
                  before: Map<String, AssetPacksPlugin.Listing> = emptyMap(), profileable: Boolean = false): (File) -> Unit =
            { out -> AssetPacksPlugin.writeBundles(apkFile, text, voice, version, before, out, profileable) }
        val unsigned = folder.root.resolve("app-arm64-v8a-release-unsigned.apk").apply { writeText("apk") }
        // each row's own refusal: one refused for another reason would pass a table that took any refusal
        val stateException = IllegalStateException::class.java
        val gradleException = GradleException::class.java
        val rows = listOf(
            Triple("a pack signed with the test key: a phone with the release build refuses it", stateException to "test key",
                write(text = listing("text", releaseKey = false))),
            Triple("a pack's content changed under the last release's version: phones that have it would not take the new one",
                gradleException to "content changed",
                write(text = listing("text", content = "new"), before = mapOf("text" to listing("text", content = "old")))),
            Triple("a pack's version below the last release's: a phone with the newer one refuses the lower", gradleException to "is below",
                write(voice = listing("voice", version = 1), before = mapOf("voice" to listing("voice", version = 2, content = "old")))),
            // build-03: the APK must be the release's own
            Triple("the APK unsigned (AGP's *-unsigned.apk: no release key set)", gradleException to "is not signed", write(apkFile = unsigned)),
            Triple("a profileable APK: a test build's", gradleException to "profileable", write(profileable = true)),
            Triple("built past the tag", gradleException to "not a release's tag", write(version = "0.6.0-5-gabc1234")),
            Triple("built off any tag", gradleException to "not a release's tag", write(version = "0.6.0-untagged")),
            Triple("built past a pre-release's tag", gradleException to "not a release's tag", write(version = "0.6.0-beta1-3-gabc1234")),
        )
        val wrong = rows.mapIndexedNotNull { i, (why, refusal, release) ->
            val (kind, word) = refusal
            val out = folder.root.resolve("out-$i")
            val thrown = runCatching { release(out) }.exceptionOrNull()
            when {
                thrown == null -> "$why: not refused"
                thrown::class.java != kind || word !in thrown.message.orEmpty() -> "$why: $thrown"
                out.exists() -> "$why: wrote ${out.list()?.toList()}"
                else -> null
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun aPreReleaseAtItsTagIsShipped() {
        val out = folder.root.resolve("out")
        AssetPacksPlugin.writeBundles(apk(), listing("text"), listing("voice"), "0.6.1-beta1", emptyMap(), out)
        assertTrue(out.resolve("youmo-0.6.1-beta1.apk").isFile)
    }

    @Test
    fun jsonStringsEscapeQuotesBackslashesAndControlCharacters() {
        assertEquals("\"a\\\"b\\\\c\\u000ad\\u0009\"", AssetPacksPlugin.jsonString("a\"b\\c\nd\t"))
        assertEquals("\"文字\"", AssetPacksPlugin.jsonString("文字"))
    }

    // contract: the build's pack ownership repeats the app's PackKind; one side changed alone shows only on import
    @Test
    fun theOwnedPathsAreTheAppsPackKinds() {
        val source = File(System.getProperty("user.dir"))
            .resolve("../../lib/ime-core/src/main/kotlin/org/fcitx/fcitx5/android/pack/PackManifest.kt").canonicalFile.readText()
        val kinds = Regex("""(\w+)\(\s*"(\w+)",\s*listOf\(([^)]*)\)""").findAll(source).associate { m ->
            m.groupValues[2] to Regex("\"([^\"]+)\"").findAll(m.groupValues[3]).map { it.groupValues[1] }.toList()
        }
        assertEquals(AssetPacksPlugin.OWNED, kinds)
    }

    // a minVersion above the version: the build would ship an app that refuses its own pack
    @Test
    fun aMinVersionAboveTheVersionFailsTheBuild() {
        assertEquals(2, AssetPacksPlugin.checkMinVersion("text", 2, 2))
        assertEquals(1, AssetPacksPlugin.checkMinVersion("text", 1, 3))
        assertThrows(GradleException::class.java) { AssetPacksPlugin.checkMinVersion("voice", 3, 2) }
    }

    // the key set as `~/…` or relative: home's and the root project's, not the Gradle daemon's directory
    @Test
    fun theKeyPathIsHomesOrTheRootProjects() {
        val root = File("/r/youmo")
        assertEquals("/home/me/keys/k.pem", AssetPacksPlugin.resolveKeyPath(" ~/keys/k.pem ", root, "/home/me"))
        assertEquals(File(root, "keys/k.pem").path, AssetPacksPlugin.resolveKeyPath("keys/k.pem", root, "/home/me"))
        assertEquals("/abs/k.pem", AssetPacksPlugin.resolveKeyPath("/abs/k.pem", root, "/home/me"))
        assertEquals("", AssetPacksPlugin.resolveKeyPath("", root, "/home/me"))
    }
}
