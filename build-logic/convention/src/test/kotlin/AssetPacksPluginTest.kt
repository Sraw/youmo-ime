/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Base64
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * The packs as the build makes them: what the app's PackShelf reads (lib/ime-core's tests take
 * packs made the same way, TestPacks) must be what this writes, signed so the app's keys take it.
 */
class AssetPacksPluginTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val raw = keys.public.encoded.let { it.copyOfRange(it.size - 32, it.size) }

    // the key pair as app/pack-keys has the test one: the private key's PEM, the public key's base64
    private fun keyFiles(dir: File = folder.newFolder()): File {
        val pem = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded)
        dir.resolve("test-ed25519.pem").writeText("-----BEGIN PRIVATE KEY-----\n$pem\n-----END PRIVATE KEY-----\n")
        dir.resolve("test.pub").writeText(Base64.getEncoder().encodeToString(raw) + "\n")
        dir.resolve("release.pub").writeText("# none in this test\n")
        return dir
    }

    private fun sources(vararg files: Pair<String, String>): File = folder.newFolder().also { dir ->
        files.forEach { (path, text) -> dir.resolve(path).apply { parentFile.mkdirs() }.writeText(text) }
    }

    private fun pack(sources: List<File>, out: File = folder.newFolder(), key: String = "", keys: File = keyFiles()): File {
        val project = ProjectBuilder.builder().withProjectDir(folder.newFolder()).build()
        val task = project.tasks.register("packTextPack", AssetPacksPlugin.PackTask::class.java).get()
        task.packId.set("text")
        task.version.set(3)
        task.format.set(1)
        task.owned.set(AssetPacksPlugin.OWNED.getValue("text"))
        task.sources.from(sources)
        task.key.set(key)
        task.testKey.set(keys.resolve("test-ed25519.pem"))
        task.releaseKeys.set(keys.resolve("release.pub"))
        task.outputDir.set(out)
        task.pack()
        return out.resolve("youmo-text-v3.youmopack")
    }

    @Test
    fun aPackIsItsManifestItsSignatureThenItsFilesStored() {
        val a = sources("engine/pinyin.data" to "pinyin", "engine/table/wbx.data" to "wubi")
        val b = sources("handwriting/chars.txt" to "一二三")
        val pack = pack(listOf(a, b))
        ZipFile(pack).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(
                listOf("manifest.json", "manifest.sig", "engine/pinyin.data", "engine/table/wbx.data", "handwriting/chars.txt"),
                entries.map { it.name },
            )
            assertTrue(entries.all { it.method == ZipEntry.STORED })
            val manifest = zip.getInputStream(zip.getEntry("manifest.json")).readBytes()
            val signature = zip.getInputStream(zip.getEntry("manifest.sig")).readBytes()
            assertEquals(64, signature.size)
            assertTrue(AssetPacksPlugin.verifies(raw, manifest, signature))
            val text = manifest.toString(Charsets.UTF_8)
            assertTrue(text, "\"pack\": \"text\"" in text && "\"version\": 3" in text && "\"format\": 1" in text)
            val sha = MessageDigest.getInstance("SHA-256").digest("一二三".toByteArray()).joinToString("") { "%02x".format(it) }
            assertTrue(text, "\"path\": \"handwriting/chars.txt\", \"size\": 9, \"sha256\": \"$sha\"" in text)
            assertTrue(text, "\"key\": \"${AssetPacksPlugin.keyName(raw)}\"" in text)
        }
        val listing = AssetPacksPlugin.Listing.read(pack.resolveSibling("youmo-text-v3.json"))
        assertEquals("text", listing.id)
        assertFalse("signed with the test key", listing.releaseKey)
    }

    @Test
    fun theSameFilesMakeTheSameBytes() {
        val keys = keyFiles()
        val a = sources("engine/pinyin.data" to "pinyin")
        val first = pack(listOf(a), keys = keys).readBytes()
        assertArrayEquals(first, pack(listOf(a), keys = keys).readBytes())
    }

    // in any time zone: east and west of UTC (an instant was each zone's own local time), no extended-time field
    @Test
    fun theSameFilesMakeTheSameBytesInAnyTimeZone() {
        val keys = keyFiles()
        val a = sources("engine/pinyin.data" to "pinyin")
        val zone = TimeZone.getDefault()
        try {
            val made = listOf("Asia/Shanghai", "America/Los_Angeles", "UTC").map {
                TimeZone.setDefault(TimeZone.getTimeZone(it))
                pack(listOf(a), keys = keys).readBytes()
            }
            made.drop(1).forEach { assertArrayEquals(made[0], it) }
            ZipFile(pack(listOf(a), keys = keys)).use { zip ->
                zip.entries().toList().forEach { entry ->
                    assertEquals(LocalDateTime.of(1980, 2, 1, 0, 0), entry.timeLocal)
                    assertEquals(null, entry.extra)
                }
            }
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    @Test
    fun aFileThePackDoesNotOwnFailsIt() {
        val error = assertThrows(IllegalStateException::class.java) { pack(listOf(sources("voice/encoder.ort" to "x"))) }
        assertTrue(error.message, "voice/encoder.ort" in error.message.orEmpty())
        // the APK's small data neither
        assertThrows(IllegalStateException::class.java) { pack(listOf(sources("engine/emoji.data" to "x"))) }
    }

    @Test
    fun aReleaseKeyTheAppDoesNotTrustFailsThePack() {
        // the key given as the release's, but release.pub lists another
        val keys = keyFiles()
        val error = assertThrows(IllegalStateException::class.java) {
            pack(listOf(sources("engine/pinyin.data" to "p")), key = keys.resolve("test-ed25519.pem").path, keys = keys)
        }
        assertTrue(error.message, "release.pub" in error.message.orEmpty())
        // listed: signed with it, and said to be the release's
        keys.resolve("release.pub").writeText(Base64.getEncoder().encodeToString(raw) + "\n")
        val pack = pack(listOf(sources("engine/pinyin.data" to "p")), key = keys.resolve("test-ed25519.pem").path, keys = keys)
        assertTrue(AssetPacksPlugin.Listing.read(pack.resolveSibling("youmo-text-v3.json")).releaseKey)
    }

    @Test
    fun aPacksChangeIsToldByItsContentAndAChangeUnderTheSameVersionFails() {
        fun listing(version: Int, content: String) = AssetPacksPlugin.Listing("text", version, 1, File("x"), "", content, true)
        assertTrue("no previous listing", AssetPacksPlugin.changed(null, listing(1, "a")))
        assertFalse(AssetPacksPlugin.changed(listing(1, "a"), listing(1, "a")))
        assertTrue(AssetPacksPlugin.changed(listing(1, "a"), listing(2, "b")))
        // the version raised with nothing changed: a new version all the same
        assertTrue(AssetPacksPlugin.changed(listing(1, "a"), listing(2, "a")))
        assertThrows(GradleException::class.java) { AssetPacksPlugin.changed(listing(1, "a"), listing(1, "b")) }
    }

    @Test
    fun theContentDigestIsTheAppsAndHasTheFormatInIt() {
        val one = listOf(Triple("voice/a", 1L, "0".repeat(64)))
        // PackManifestTest holds the app's to the same hex
        assertEquals("22673f57195026c437f22687cb73a037475ed8f8f1a6d27980e4b389971da34e", AssetPacksPlugin.contentSha256(1, one))
        assertFalse(AssetPacksPlugin.contentSha256(1, one) == AssetPacksPlugin.contentSha256(2, one))
        // and the listing beside a pack says it
        val pack = pack(listOf(sources("engine/pinyin.data" to "pinyin")))
        val sha = MessageDigest.getInstance("SHA-256").digest("pinyin".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(
            AssetPacksPlugin.contentSha256(1, listOf(Triple("engine/pinyin.data", 6L, sha))),
            AssetPacksPlugin.Listing.read(pack.resolveSibling("youmo-text-v3.json")).contentSha256,
        )
    }

    @Test
    fun aReleaseComparesWithTheLastOnesListingOrSaysItIsTheFirst() {
        val error = assertThrows(GradleException::class.java) { AssetPacksPlugin.previousListing(null, false) }
        assertTrue(error.message, "firstPackRelease" in error.message.orEmpty() && AssetPacksPlugin.RELEASED in error.message.orEmpty())
        assertEquals(emptyMap<String, AssetPacksPlugin.Listing>(), AssetPacksPlugin.previousListing(null, true))
        val listing = folder.newFile("release-packs.json").apply {
            writeText(
                "{\"app\": \"0.6.0\", \"packs\": [\n" +
                    "  {\"pack\": \"text\", \"version\": 1, \"format\": 1, \"file\": \"youmo-text-v1.youmopack\", \"size\": 3, \"sha256\": \"a\", \"contentSha256\": \"b\", \"changed\": true}\n]}\n",
            )
        }
        val read = AssetPacksPlugin.previousListing(listing, false)
        assertEquals(setOf("text"), read.keys)
        assertEquals("b", read.getValue("text").contentSha256)
        assertEquals(1, read.getValue("text").version)
        // a listing there and the first release said: one of the two is a mistake
        assertThrows(GradleException::class.java) { AssetPacksPlugin.previousListing(listing, true) }
    }

    @Test
    fun theCommittedTestKeyIsTestPubsAndTheReleaseKeyIsTheFormalOne() {
        val dir = File(System.getProperty("user.dir")).resolve("../../app/pack-keys").canonicalFile
        val test = AssetPacksPlugin.readKeys(dir.resolve("test.pub"))
        assertEquals(1, test.size)
        assertEquals(0, AssetPacksPlugin.indexOfKey(AssetPacksPlugin.privateKey(dir.resolve("test-ed25519.pem").readText()), test))
        val release = AssetPacksPlugin.readKeys(dir.resolve("release.pub"))
        assertTrue("release.pub has a key", release.isNotEmpty())
        assertTrue("the test key is never a release key", release.none { it.contentEquals(test[0]) })
    }
}
