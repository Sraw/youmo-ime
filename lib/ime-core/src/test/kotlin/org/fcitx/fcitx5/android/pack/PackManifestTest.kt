/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackManifestTest {

    private val packs = TestPacks()

    private fun parse(s: String) = PackManifest.parse(s.toByteArray())

    private fun fails(s: String) = assertTrue(s, runCatching { parse(s) }.exceptionOrNull() is IllegalArgumentException)

    @Test
    fun `each asset path is one pack's or the APK's`() {
        assertEquals(PackKind.TEXT, PackKind.of("engine/pinyin.data"))
        assertEquals(PackKind.TEXT, PackKind.of("engine/sentence-model-large.safetensors"))
        assertEquals(PackKind.TEXT, PackKind.of("engine/table/wbx.data"))
        assertEquals(PackKind.TEXT, PackKind.of("engine/stroke.data"))
        assertEquals(PackKind.TEXT, PackKind.of("handwriting/char_image.ort"))
        assertEquals(PackKind.VOICE, PackKind.of("voice/encoder.ort"))
        // small data the code changes with: the APK's
        for (apk in listOf("engine/emoji.data", "engine/chaizi.data", "engine/charinfo.data", "engine/opencc-s2tw.data", "learn/TSCharacters.txt", "pkgdata/punctuation/punc.mb.zh_CN")) {
            assertNull(apk, PackKind.of(apk))
        }
        assertEquals(PackKind.VOICE, PackKind.byId("voice"))
        assertNull(PackKind.byId("music"))
    }

    @Test
    fun `a manifest reads as the build writes it`() {
        val m = PackManifest.parse(packs.manifest(PackKind.TEXT, TestPacks.TEXT_FILES, version = 7, format = 2))
        assertEquals(PackKind.TEXT, m.kind)
        assertEquals(7, m.version)
        assertEquals(2, m.format)
        assertEquals(PackManifest.keyName(packs.publicKey), m.key)
        assertEquals(TestPacks.TEXT_FILES.keys.toList(), m.files.map { it.path })
        assertEquals(1004L + "一二三".toByteArray().size, m.size)
        assertEquals(TestPacks.sha256(TestPacks.TEXT_FILES.getValue("engine/pinyin.data")), m.files[0].sha256)
    }

    @Test
    fun `the content digest goes by the files, not their order or the key`() {
        val a = PackManifest.parse(packs.manifest(PackKind.TEXT, TestPacks.TEXT_FILES))
        val b = PackManifest.parse(TestPacks().manifest(PackKind.TEXT, TestPacks.TEXT_FILES.entries.reversed().associate { it.toPair() }))
        assertEquals(a.contentSha256, b.contentSha256)
        assertTrue(a.sameAs(b))
        val c = PackManifest.parse(packs.manifest(PackKind.TEXT, TestPacks.TEXT_FILES + ("engine/english.data" to byteArrayOf(1))))
        assertNotEquals(a.contentSha256, c.contentSha256)
        assertFalse(a.sameAs(c))
        assertFalse(a.sameAs(PackManifest.parse(packs.manifest(PackKind.TEXT, TestPacks.TEXT_FILES, version = 2))))
        // contract: the digest of one file, as the release listing computes it; build-logic AssetPacksPluginTest
        // holds the same hex, change both or neither
        val one = PackManifest(PackKind.VOICE, 1, 1, listOf(PackManifest.Entry("voice/a", 1, "0".repeat(64))))
        assertEquals(TestPacks.sha256("format\t1\nvoice/a\t1\t${"0".repeat(64)}\n".toByteArray()), one.contentSha256)
        assertEquals("22673f57195026c437f22687cb73a037475ed8f8f1a6d27980e4b389971da34e", one.contentSha256)
        // the format is in it: the same files in another format are another pack
        val other = PackManifest(PackKind.VOICE, 1, 2, one.files)
        assertNotEquals(one.contentSha256, other.contentSha256)
        assertFalse(one.sameAs(other))
    }

    @Test
    fun `what is not a manifest says why`() {
        fails("[]")
        fails("{}")
        fails("""{"pack": "text"}""")
        fails("""{"pack": "music", "version": 1, "format": 1, "files": []}""")
        fails("""{"pack": "text", "version": 1, "format": 1, "files": [1]}""")
        fails("""{"pack": "text", "version": 1, "format": 1, "files": [{"size": 1, "sha256": "${"0".repeat(64)}"}]}""")
        fails("""{"pack": "text", "version": 1, "format": 1, "files": [{"path": "engine/pinyin.data", "size": 1}]}""")
        fails("""{"pack": "text", "version": 1, "format": 1, "files": [{"path": "engine/pinyin.data", "size": 1, "sha256": "XYZ"}]}""")
        fails("""{"pack": "text", "version": 1, "format": 1, "files": [{"path": "engine/pinyin.data", "size": -1, "sha256": "${"0".repeat(64)}"}]}""")
        fails("""{"pack": "text", "version": 1.5, "format": 1, "files": []}""")
        fails("""{"pack": "text", "version": 1, "format": 3000000000, "files": []}""")
        fails("""{"pack": "text", "version": 1, "files": []}""")
        val f = """{"path": "engine/pinyin.data", "size": 1, "sha256": "${"0".repeat(64)}"}"""
        fails("""{"pack": "text", "version": 1, "format": 1, "files": [$f, $f]}""")
        assertTrue(runCatching { PackManifest.parse(ByteArray(PackManifest.MAX_BYTES + 1) { ' '.code.toByte() }) }.isFailure)
        // no key named: read all the same
        assertEquals("", parse("""{"pack": "voice", "version": 1, "format": 1, "files": []}""").key)
    }

    @Test
    fun `only plain relative paths are safe`() {
        for (ok in listOf("a", "engine/table/wbx.data", "voice/silero_vad.onnx", "a-b/c.d")) assertTrue(ok, PackManifest.safePath(ok))
        for (bad in listOf("", "/etc/passwd", "a//b", "a/", "../a", "a/../b", "./a", "a/.", "a\\b", "a b", "名")) assertFalse(bad, PackManifest.safePath(bad))
    }
}
