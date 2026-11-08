/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.pack

import org.fcitx.fcitx5.android.pack.TestPacks.Companion.TEXT_FILES
import org.fcitx.fcitx5.android.pack.TestPacks.Companion.VOICE_FILES
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

class PackShelfTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val packs = TestPacks()
    private val needs = mapOf(PackKind.TEXT to PackRequirement(2, 3), PackKind.VOICE to PackRequirement(1, 1))
    private val root by lazy { tmp.newFolder("packs") }

    private fun shelf(trusted: List<ByteArray> = listOf(packs.publicKey), margin: Long = 0) =
        PackShelf(root, trusted, needs, margin).apply { load() }

    private fun text(version: Int = 3, format: Int = 2, files: Map<String, ByteArray> = TEXT_FILES) =
        packs.pack(PackKind.TEXT, files, version, format)

    private fun PackShelf.take(bytes: ByteArray) = import(ByteArrayInputStream(bytes))

    private inline fun <reified T : Refusal> ImportResult.refused(): T {
        assertTrue("refused, not $this", this is ImportResult.Refused)
        val why = (this as ImportResult.Refused).why
        assertTrue("$why", why is T)
        return why as T
    }

    private fun assertReady(shelf: PackShelf, kind: PackKind, version: Int, files: Map<String, ByteArray>) {
        val status = shelf.status(kind)
        assertTrue("$status", status is PackStatus.Ready)
        assertEquals(version, (status as PackStatus.Ready).manifest.version)
        val dir = shelf.dir(kind)!!
        files.forEach { (path, bytes) -> assertArrayEquals(path, bytes, File(dir, path).readBytes()) }
    }

    // what is in root beside the packs in use: nothing once loaded
    private fun leftovers(kind: PackKind) =
        File(root, kind.id).listFiles().orEmpty().map { it.name }.filter { it != PackShelf.CURRENT }

    @Test
    fun `a good pack is imported, used, and found again by the next start`() {
        val shelf = shelf()
        assertSame(PackStatus.Missing, shelf.status(PackKind.TEXT))
        assertNull(shelf.dir(PackKind.TEXT))
        var last = 0L
        val result = shelf.import(ByteArrayInputStream(text())) { copied, total ->
            assertTrue(copied in last..total)
            last = copied
        }
        assertTrue(result is ImportResult.Imported)
        assertNull((result as ImportResult.Imported).replaced)
        assertEquals(TEXT_FILES.values.sumOf { it.size }.toLong(), last)
        assertReady(shelf, PackKind.TEXT, 3, TEXT_FILES)
        assertSame(PackStatus.Missing, shelf.status(PackKind.VOICE))
        // a restart: the app's next process
        assertReady(shelf(), PackKind.TEXT, 3, TEXT_FILES)
        assertEquals(1, leftovers(PackKind.TEXT).size)
    }

    // each rename's directory synced after it: the generation's, then `current`'s (review L8)
    @Test
    fun `an import syncs the pack's directory after each rename`() {
        val synced = ArrayList<String>()
        val shelf = PackShelf(root, listOf(packs.publicKey), needs, 0) { synced += it.name }.apply { load() }
        shelf.take(text())
        assertEquals(listOf(PackKind.TEXT.id, PackKind.TEXT.id), synced)
        // a directory can be synced here (Linux, macOS), as on a phone
        assertTrue(PackShelf.syncDirectory(root))
    }

    @Test
    fun `the same pack again copies nothing`() {
        val shelf = shelf()
        shelf.take(text())
        val dir = shelf.dir(PackKind.TEXT)
        assertTrue(shelf.take(text()) is ImportResult.AlreadyThere)
        assertEquals(dir, shelf.dir(PackKind.TEXT))
    }

    // a byte of a file in use changed, its size not (what a start does not see)
    private fun damage(shelf: PackShelf, kind: PackKind, path: String) {
        val file = File(shelf.dir(kind)!!, path)
        val bytes = file.readBytes()
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        file.writeBytes(bytes)
    }

    @Test
    fun `the same pack again over one damaged is copied anew, not already there`() {
        val shelf = shelf()
        shelf.take(text())
        val dir = shelf.dir(PackKind.TEXT)
        damage(shelf, PackKind.TEXT, "engine/pinyin.data")
        val result = shelf.take(text())
        assertTrue("$result", result is ImportResult.Imported)
        assertNotEquals(dir, shelf.dir(PackKind.TEXT))
        assertReady(shelf, PackKind.TEXT, 3, TEXT_FILES)
        shelf.sweep()
        assertEquals(listOf(shelf.dir(PackKind.TEXT)!!.name), leftovers(PackKind.TEXT))
    }

    @Test
    fun `a pack read through and found changed is damaged till imported again`() {
        val shelf = shelf()
        shelf.take(text())
        shelf.take(packs.pack(PackKind.VOICE, VOICE_FILES))
        // as it was copied: nothing changes
        assertFalse(shelf.verify(PackKind.TEXT))
        assertReady(shelf, PackKind.TEXT, 3, TEXT_FILES)
        // none there: nothing to read
        assertFalse(PackShelf(tmp.newFolder(), listOf(packs.publicKey), needs).apply { load() }.verify(PackKind.TEXT))
        damage(shelf, PackKind.TEXT, "engine/pinyin.data")
        assertTrue(shelf.verify(PackKind.TEXT))
        val status = shelf.status(PackKind.TEXT)
        assertTrue("$status", status is PackStatus.Damaged)
        assertEquals(3, (status as PackStatus.Damaged).manifest.version)
        assertNull(shelf.dir(PackKind.TEXT))
        // the other pack as it was
        assertFalse(shelf.verify(PackKind.VOICE))
        assertTrue(shelf.status(PackKind.VOICE) is PackStatus.Ready)
        // a file gone is damage too
        File(shelf.dir(PackKind.VOICE)!!, VOICE_FILES.keys.first()).delete()
        assertTrue(shelf.verify(PackKind.VOICE))
        // the same pack imported again mends it
        val result = shelf.take(text())
        assertTrue("$result", result is ImportResult.Imported)
        assertEquals(3, (result as ImportResult.Imported).replaced?.version)
        assertReady(shelf, PackKind.TEXT, 3, TEXT_FILES)
        assertFalse(shelf.verify(PackKind.TEXT))
    }

    // review M-2: the damage found is kept, a restart's load reads it (the gate up at once), till imported again
    @Test
    fun `a pack found damaged stays so after a restart till imported again`() {
        val shelf = shelf()
        shelf.take(text())
        val gen = shelf.dir(PackKind.TEXT)!!
        damage(shelf, PackKind.TEXT, "engine/pinyin.data")
        assertTrue(shelf.verify(PackKind.TEXT))
        assertTrue(File(gen, PackShelf.DAMAGED).exists())
        val restarted = shelf()
        val status = restarted.status(PackKind.TEXT)
        assertTrue("$status", status is PackStatus.Damaged)
        assertEquals(3, (status as PackStatus.Damaged).manifest.version)
        assertNull(restarted.dir(PackKind.TEXT))
        assertSame(VerifyDue.NONE, restarted.due(PackKind.TEXT))
        // imported again: mended, and so after the next restart
        val result = restarted.take(text())
        assertTrue("$result", result is ImportResult.Imported)
        assertReady(restarted, PackKind.TEXT, 3, TEXT_FILES)
        assertReady(shelf(), PackKind.TEXT, 3, TEXT_FILES)
        assertFalse(shelf().verify(PackKind.TEXT))
    }

    // review M-1: read through at the first start after an import, then once its 30 days are past
    @Test
    fun `a pack is read through once after its import, then after 30 days, the time kept by the next start`() {
        var now = 1_000_000_000_000L
        fun shelf() = PackShelf(root, listOf(packs.publicKey), needs, 0, { now }).apply { load() }
        val shelf = shelf()
        assertSame(VerifyDue.NONE, shelf.due(PackKind.TEXT))
        shelf.take(text())
        assertSame(VerifyDue.UNVERIFIED, shelf.due(PackKind.TEXT))
        // the import's own check is of the bytes as written, not as kept: still due at the next start
        assertSame(VerifyDue.UNVERIFIED, shelf().due(PackKind.TEXT))
        assertFalse(shelf.verify(PackKind.TEXT))
        assertSame(VerifyDue.NONE, shelf.due(PackKind.TEXT))
        assertEquals("$now", File(shelf.dir(PackKind.TEXT), PackShelf.VERIFIED).readText())
        val read = now
        now += PackShelf.VERIFY_EVERY_MS
        assertSame(VerifyDue.NONE, shelf().due(PackKind.TEXT))
        now += 1
        assertSame(VerifyDue.STALE, shelf().due(PackKind.TEXT))
        // the clock set back past a day: when it was read is not known
        now = read - 2 * 24 * 60 * 60 * 1000L
        assertSame(VerifyDue.STALE, shelf().due(PackKind.TEXT))
        now = read - 60_000
        assertSame(VerifyDue.NONE, shelf().due(PackKind.TEXT))
        // the mark half written: read through again
        File(shelf.dir(PackKind.TEXT), PackShelf.VERIFIED).writeText("x")
        assertSame(VerifyDue.UNVERIFIED, shelf().due(PackKind.TEXT))
        // the same pack imported again is read through, and so marked
        val again = shelf()
        assertTrue(again.take(text()) is ImportResult.AlreadyThere)
        assertSame(VerifyDue.NONE, again.due(PackKind.TEXT))
        assertSame(VerifyDue.NONE, shelf().due(PackKind.TEXT))
    }

    // review L-3: a file gone is damage; one the disk cannot read now says nothing of the pack
    @Test
    fun `a file that cannot be read now is neither damage nor verified`() {
        var failing = true
        val shelf = PackShelf(root, listOf(packs.publicKey), needs, 0, openFile = { file ->
            if (failing && file.name == "pinyin.data") throw IOException("EIO") else file.inputStream()
        }).apply { load() }
        shelf.take(text())
        assertFalse(shelf.verify(PackKind.TEXT))
        assertTrue(shelf.status(PackKind.TEXT) is PackStatus.Ready)
        assertSame(VerifyDue.UNVERIFIED, shelf.due(PackKind.TEXT))
        assertFalse(File(shelf.dir(PackKind.TEXT), PackShelf.DAMAGED).exists())
        // the same pack again, unreadable: copied anew, not taken for there
        assertTrue(shelf.take(text()) is ImportResult.Imported)
        failing = false
        assertFalse(shelf.verify(PackKind.TEXT))
        assertSame(VerifyDue.NONE, shelf.due(PackKind.TEXT))
    }

    @Test
    fun `a newer pack replaces the old one, which is deleted`() {
        val shelf = shelf()
        shelf.take(text())
        val old = shelf.dir(PackKind.TEXT)!!
        val files = TEXT_FILES + ("engine/english.data" to "en".toByteArray())
        val result = shelf.take(text(version = 4, files = files))
        assertEquals(3, (result as ImportResult.Imported).replaced?.version)
        assertReady(shelf, PackKind.TEXT, 4, files)
        // kept till swept: the app's host restarted on the new one first (review L3)
        assertTrue(old.exists())
        shelf.sweep()
        assertFalse(old.exists())
        assertEquals(1, leftovers(PackKind.TEXT).size)
        assertReady(shelf, PackKind.TEXT, 4, files)
    }

    // a pack gone wrong taken back: the older one imported over the newer, the result saying what it replaced
    @Test
    fun `an older pack is imported over a newer one`() {
        val shelf = shelf()
        shelf.take(text(version = 4))
        val result = shelf.take(text(version = 3, files = TEXT_FILES + ("engine/english.data" to "en".toByteArray())))
        assertTrue("$result", result is ImportResult.Imported)
        assertEquals(4, (result as ImportResult.Imported).replaced?.version)
        assertEquals(3, result.manifest.version)
        assertReady(shelf, PackKind.TEXT, 3, TEXT_FILES)
    }

    @Test
    fun `a voice pack first, then the text pack, each its own`() {
        val shelf = shelf()
        assertTrue(shelf.take(packs.pack(PackKind.VOICE, VOICE_FILES)) is ImportResult.Imported)
        assertSame(PackStatus.Missing, shelf.status(PackKind.TEXT))
        assertTrue(shelf.take(text()) is ImportResult.Imported)
        assertReady(shelf, PackKind.VOICE, 1, VOICE_FILES)
        assertReady(shelf, PackKind.TEXT, 3, TEXT_FILES)
        val again = shelf()
        assertReady(again, PackKind.VOICE, 1, VOICE_FILES)
        assertReady(again, PackKind.TEXT, 3, TEXT_FILES)
    }

    @Test
    fun `a pack signed by another key is refused, and nothing is copied`() {
        val shelf = shelf()
        val other = TestPacks()
        val why = shelf.take(other.pack(PackKind.TEXT, TEXT_FILES, 3, 2)).refused<Refusal.BadSignature>()
        assertEquals(PackManifest.keyName(other.publicKey), why.key)
        assertSame(PackStatus.Missing, shelf.status(PackKind.TEXT))
        assertEquals(emptyList<String>(), leftovers(PackKind.TEXT))
    }

    @Test
    fun `a manifest changed after it was signed is refused`() {
        val manifest = packs.manifest(PackKind.TEXT, TEXT_FILES, 3, 2)
        val signature = packs.sign(manifest)
        val changed = String(manifest).replace("\"version\": 3", "\"version\": 9").toByteArray()
        shelf().take(packs.pack(PackKind.TEXT, TEXT_FILES, manifest = changed, signature = signature)).refused<Refusal.BadSignature>()
    }

    @Test
    fun `a file whose hash differs is refused, the old pack kept, the copy deleted`() {
        val shelf = shelf()
        shelf.take(text())
        val wrong = packs.manifest(PackKind.TEXT, TEXT_FILES, 4, 2, sha = mapOf("engine/table/wbx.data" to "0".repeat(64)))
        val why = shelf.take(packs.pack(PackKind.TEXT, TEXT_FILES, manifest = wrong)).refused<Refusal.BadFile>()
        assertEquals("engine/table/wbx.data", why.path)
        assertReady(shelf, PackKind.TEXT, 3, TEXT_FILES)
        assertEquals(1, leftovers(PackKind.TEXT).size)
    }

    @Test
    fun `a file's bytes changed in the zip are a bad file, not an unreadable one`() {
        val bytes = text()
        // the pinyin file's 1000 counting bytes: the 500th changed
        val at = indexOf(bytes, ByteArray(1000) { it.toByte() }) + 500
        bytes[at] = (bytes[at] + 1).toByte()
        assertEquals("engine/pinyin.data", shelf().take(bytes).refused<Refusal.BadFile>().path)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int =
        (0..haystack.size - needle.size).first { i -> needle.indices.all { haystack[i + it] == needle[it] } }

    @Test
    fun `files not as the manifest lists them are refused`() {
        val shelf = shelf()
        // one more than it lists, one fewer, one longer, one outside the pack's directory
        val extra = TEXT_FILES + ("engine/table/zrm.data" to byteArrayOf(1))
        assertEquals("engine/table/zrm.data", shelf.take(packs.pack(PackKind.TEXT, TEXT_FILES, 3, 2, entries = extra)).refused<Refusal.BadFile>().path)
        val fewer = TEXT_FILES - "handwriting/chars.txt"
        assertEquals("handwriting/chars.txt", shelf.take(packs.pack(PackKind.TEXT, TEXT_FILES, 3, 2, entries = fewer)).refused<Refusal.BadFile>().path)
        val longer = TEXT_FILES + ("engine/table/wbx.data" to "wubi!".toByteArray())
        assertEquals("engine/table/wbx.data", shelf.take(packs.pack(PackKind.TEXT, TEXT_FILES, 3, 2, entries = longer)).refused<Refusal.BadFile>().path)
        val slip = TEXT_FILES + ("../../evil" to byteArrayOf(1))
        shelf.take(packs.pack(PackKind.TEXT, TEXT_FILES, 3, 2, entries = slip)).refused<Refusal.BadFile>()
        assertFalse(File(root.parentFile, "evil").exists())
        assertSame(PackStatus.Missing, shelf.status(PackKind.TEXT))
        assertEquals(emptyList<String>(), leftovers(PackKind.TEXT))
    }

    // a file listed once, in the zip twice (the same bytes both times): the zip writer refuses a
    // name twice, so the second is written under a name of the same length and patched (review L1: M1)
    @Test
    fun `a file twice in the zip is refused`() {
        val shelf = shelf()
        val twin = "engine/table/wbx.datb"
        val bytes = packs.pack(PackKind.TEXT, TEXT_FILES, 3, 2, entries = TEXT_FILES + (twin to TEXT_FILES.getValue("engine/table/wbx.data")))
        val from = twin.toByteArray()
        val to = "engine/table/wbx.data".toByteArray()
        var patched = 0
        var i = 0
        while (i <= bytes.size - from.size) {
            if ((from.indices).all { bytes[i + it] == from[it] }) {
                to.copyInto(bytes, i)
                patched++
            }
            i++
        }
        assertEquals("the local header's name and the central directory's", 2, patched)
        assertEquals("engine/table/wbx.data", shelf.take(bytes).refused<Refusal.BadFile>().path)
        assertSame(PackStatus.Missing, shelf.status(PackKind.TEXT))
    }

    // a file longer than listed is refused as its bytes pass the size, not after all of them are
    // written: the copy never holds more than the pack's size (review L1: M2)
    @Test
    fun `a file longer than listed is refused before it is written past its size`() {
        val shelf = shelf()
        val longer = TEXT_FILES + ("handwriting/chars.txt" to ByteArray(1 shl 20))
        var most = 0L
        var total = 0L
        val result = shelf.import(ByteArrayInputStream(packs.pack(PackKind.TEXT, TEXT_FILES, 3, 2, entries = longer))) { copied, of ->
            most = maxOf(most, copied)
            total = of
        }
        assertEquals("handwriting/chars.txt", result.refused<Refusal.BadFile>().path)
        assertTrue("$most > $total", most <= total)
    }

    // the manifest and its signature first, by name: the right bytes under other names, or the two
    // the other way round, are no pack (review L1: M10)
    @Test
    fun `the manifest and its signature must be the first two entries by name`() {
        val shelf = shelf()
        val manifest = packs.manifest(PackKind.TEXT, TEXT_FILES, 3, 2)
        val signature = packs.sign(manifest)
        fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(out).use { z -> entries.forEach { (name, bytes) -> TestPacks.stored(z, name, bytes) } }
            return out.toByteArray()
        }
        val files = TEXT_FILES.map { it.key to it.value }.toTypedArray()
        // as built: imported (the helper makes good packs)
        assertTrue(shelf.take(zip(PackManifest.NAME to manifest, PackManifest.SIGNATURE to signature, *files)) is ImportResult.Imported)
        val other = shelf()
        other.take(zip("pack.json" to manifest, PackManifest.SIGNATURE to signature, *files)).refused<Refusal.NotAPack>()
        other.take(zip(PackManifest.NAME to manifest, "pack.sig" to signature, *files)).refused<Refusal.NotAPack>()
        other.take(zip(PackManifest.SIGNATURE to signature, PackManifest.NAME to manifest, *files)).refused<Refusal.NotAPack>()
    }

    @Test
    fun `a manifest naming a path not its pack's, or an unsafe one, is no pack`() {
        val shelf = shelf()
        shelf.take(packs.pack(PackKind.TEXT, mapOf("voice/encoder.ort" to byteArrayOf(1)), 3, 2)).refused<Refusal.NotAPack>()
        shelf.take(packs.pack(PackKind.TEXT, mapOf("engine/table/../x" to byteArrayOf(1)), 3, 2)).refused<Refusal.NotAPack>()
        val unknown = """{"pack": "music", "version": 1, "format": 1, "files": []}""".toByteArray()
        shelf.take(packs.pack(PackKind.TEXT, emptyMap(), manifest = unknown)).refused<Refusal.NotAPack>()
    }

    @Test
    fun `what is no pack at all is said so`() {
        val shelf = shelf()
        shelf.take("hello".toByteArray()).refused<Refusal.NotAPack>()
        shelf.take(ByteArray(0)).refused<Refusal.NotAPack>()
        // a zip whose first entry is not the manifest
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { TestPacks.stored(it, "readme.txt", byteArrayOf(1)) }
        shelf.take(out.toByteArray()).refused<Refusal.NotAPack>()
        // a signature entry far too long
        val manifest = packs.manifest(PackKind.TEXT, TEXT_FILES, 3, 2)
        shelf.take(packs.pack(PackKind.TEXT, TEXT_FILES, manifest = manifest, signature = ByteArray(100))).refused<Refusal.NotAPack>()
    }

    @Test
    fun `an entry name that is not UTF-8 is no pack, not a crash`() {
        val shelf = shelf()
        // ZipInputStream reads names as UTF-8 and throws IllegalArgumentException on a malformed one:
        // it escaped the import and took the app's process with it. The first entry, and a file's
        val bad = String(byteArrayOf(0xff.toByte(), 'a'.code.toByte()), Charsets.ISO_8859_1)
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out, Charsets.ISO_8859_1).use { TestPacks.stored(it, bad, byteArrayOf(1)) }
        shelf.take(out.toByteArray()).refused<Refusal.NotAPack>()
        val files = TEXT_FILES + ("engine/table/$bad" to byteArrayOf(1))
        shelf.take(packs.pack(PackKind.TEXT, TEXT_FILES, 3, 2, entries = files, charset = Charsets.ISO_8859_1)).refused<Refusal.NotAPack>()
        assertSame(PackStatus.Missing, shelf.status(PackKind.TEXT))
        assertEquals(emptyList<String>(), leftovers(PackKind.TEXT))
        // and the shelf takes a good pack after them
        assertTrue(shelf.take(text()) is ImportResult.Imported)
    }

    @Test
    fun `a pack of another format or too old a version is refused, saying what is needed`() {
        val shelf = shelf()
        val older = shelf.take(text(format = 1)).refused<Refusal.Incompatible>()
        assertEquals(PackRequirement(2, 3), older.needed)
        assertEquals(1, older.manifest.format)
        shelf.take(text(format = 3)).refused<Refusal.Incompatible>()
        assertEquals(2, shelf.take(text(version = 2)).refused<Refusal.Incompatible>().manifest.version)
        assertSame(PackStatus.Missing, shelf.status(PackKind.TEXT))
    }

    @Test
    fun `a pack the next build no longer takes reads as needing an update, or a newer app`() {
        PackShelf(root, listOf(packs.publicKey), mapOf(PackKind.TEXT to PackRequirement(2, 3), PackKind.VOICE to PackRequirement(1, 1)), 0)
            .apply { load() }.take(text())
        // the next APK reads format 3: the pack needs updating
        val newer = PackShelf(root, listOf(packs.publicKey), mapOf(PackKind.TEXT to PackRequirement(3, 1), PackKind.VOICE to PackRequirement(1, 1)), 0)
        newer.load()
        val status = newer.status(PackKind.TEXT)
        assertTrue("$status", status is PackStatus.NeedsUpdate)
        assertEquals(3, (status as PackStatus.NeedsUpdate).needed.format)
        assertNull(newer.dir(PackKind.TEXT))
        // needs version 4
        val later = PackShelf(root, listOf(packs.publicKey), mapOf(PackKind.TEXT to PackRequirement(2, 4), PackKind.VOICE to PackRequirement(1, 1)), 0)
        later.load()
        assertTrue(later.status(PackKind.TEXT) is PackStatus.NeedsUpdate)
        // an older APK, which reads format 1, given it
        val older = PackShelf(root, listOf(packs.publicKey), mapOf(PackKind.TEXT to PackRequirement(1, 1), PackKind.VOICE to PackRequirement(1, 1)), 0)
        older.load()
        assertTrue(older.status(PackKind.TEXT) is PackStatus.NeedsNewerApp)
        // its update imported over it
        newer.take(text(version = 5, format = 3))
        assertReady(newer, PackKind.TEXT, 5, TEXT_FILES)
    }

    @Test
    fun `a pack whose key the next build no longer trusts is not used`() {
        shelf().take(text())
        val rotated = shelf(trusted = listOf(TestPacks().publicKey))
        assertSame(PackStatus.Missing, rotated.status(PackKind.TEXT))
        // both trusted, the old key's packs still read
        assertReady(shelf(trusted = listOf(TestPacks().publicKey, packs.publicKey)), PackKind.TEXT, 3, TEXT_FILES)
    }

    @Test
    fun `not enough room is refused before a byte is copied`() {
        val shelf = shelf(margin = Long.MAX_VALUE / 2)
        val why = shelf.take(text()).refused<Refusal.NoSpace>()
        assertTrue(why.need > why.free)
        assertEquals(emptyList<String>(), leftovers(PackKind.TEXT))
    }

    // a file that stops partway: the user's storage taken away, or the process killed as it reads
    private class Breaking(input: InputStream, private var left: Int) : FilterInputStream(input) {
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) throw IOException("gone")
            val n = super.read(b, off, minOf(len, left))
            if (n > 0) left -= n
            return n
        }
    }

    @Test
    fun `a copy cut short keeps the pack in use, and the half copied is deleted`() {
        val shelf = shelf()
        shelf.take(text())
        val next = text(version = 4)
        val why = shelf.import(Breaking(ByteArrayInputStream(next), next.size / 2)).refused<Refusal.Unreadable>()
        assertEquals("gone", why.detail)
        assertReady(shelf, PackKind.TEXT, 3, TEXT_FILES)
        assertEquals(1, leftovers(PackKind.TEXT).size)
    }

    @Test
    fun `what an import killed outright left is deleted at the next start, the old pack used`() {
        shelf().take(text())
        val dir = File(root, PackKind.TEXT.id)
        // killed while copying, and between the generation's rename and the pointer's
        File(dir, ".staging-1/engine").mkdirs()
        File(dir, ".staging-1/engine/pinyin.data").writeText("half")
        File(dir, "g9-9/engine").mkdirs()
        File(dir, "${PackShelf.CURRENT}.tmp").writeText("g9-9")
        val next = shelf()
        assertReady(next, PackKind.TEXT, 3, TEXT_FILES)
        assertEquals(listOf(next.dir(PackKind.TEXT)!!.name), leftovers(PackKind.TEXT))
    }

    // its manifest signed and there, a file of another size: damaged, said so, not as if never imported
    @Test
    fun `a pack whose files are not all there reads as damaged`() {
        val shelf = shelf()
        shelf.take(text())
        File(shelf.dir(PackKind.TEXT), "engine/table/wbx.data").writeText("w")
        val status = shelf().status(PackKind.TEXT)
        assertTrue("$status", status is PackStatus.Damaged)
        // the pointer naming nothing, or something unsafe
        File(root, "text/current").writeText("../voice")
        assertSame(PackStatus.Missing, shelf().status(PackKind.TEXT))
    }

    @Test
    fun `a generation's manifest unreadable or not its pack's reads as missing`() {
        val shelf = shelf()
        shelf.take(text())
        val gen = shelf.dir(PackKind.TEXT)!!
        File(gen, PackManifest.SIGNATURE).delete()
        assertSame(PackStatus.Missing, shelf().status(PackKind.TEXT))
        // the voice pack's manifest, signed, in the text pack's place
        val voice = packs.manifest(PackKind.VOICE, emptyMap())
        File(gen, PackManifest.NAME).writeBytes(voice)
        File(gen, PackManifest.SIGNATURE).writeBytes(packs.sign(voice))
        assertSame(PackStatus.Missing, shelf().status(PackKind.TEXT))
        // signed, but no manifest
        val junk = "[]".toByteArray()
        File(gen, PackManifest.NAME).writeBytes(junk)
        File(gen, PackManifest.SIGNATURE).writeBytes(packs.sign(junk))
        assertSame(PackStatus.Missing, shelf().status(PackKind.TEXT))
    }

    @Test
    fun `a shelf needs a key and a requirement for each pack`() {
        assertTrue(runCatching { PackShelf(root, emptyList(), needs) }.isFailure)
        assertTrue(runCatching { PackShelf(root, listOf(ByteArray(31)), needs) }.isFailure)
        assertTrue(runCatching { PackShelf(root, listOf(packs.publicKey), needs - PackKind.VOICE) }.isFailure)
    }
}
