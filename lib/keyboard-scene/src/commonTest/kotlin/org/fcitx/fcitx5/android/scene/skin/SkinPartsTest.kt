/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.SoundSamples
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.wav
import org.fcitx.fcitx5.android.scene.theme.SoundFile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkinPartsTest {
    private val legacy: (ByteArray) -> String = { "gb:" + it.size }

    @Test
    fun textByItsMark() {
        assertEquals("名a", SkinText.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x0D, 0x54, 0x61, 0), legacy))
        assertEquals("名a", SkinText.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x54, 0x0D, 0, 0x61), legacy))
        assertEquals("名", SkinText.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "名".encodeToByteArray(), legacy))
        assertEquals("名", SkinText.decode("名".encodeToByteArray(), legacy))
        // GBK's 名 (0xC3FB) is no UTF-8: the platform's legacy reading
        assertEquals("gb:2", SkinText.decode(byteArrayOf(0xC3.toByte(), 0xFB.toByte()), legacy))
    }

    @Test
    fun iniForgives() {
        val ini = SkinText.ini(
            "a=before any section\n[S]\nk = v \n; c=1\n# c=2\n// c=3\nk=second\nnot a pair\n=empty key\n[]\nlost=1\n[ T ]\nx=1\n[S]\nm=2\n",
        )
        assertEquals(mapOf("S" to mapOf("k" to "v", "m" to "2"), "T" to mapOf("x" to "1")), ini)
        // a crafted file's millions of sections or keys stop at the caps
        val many = SkinText.ini((0..SkinText.MAX_SECTIONS + 5).joinToString("") { "[s$it]\nk=1\n" })
        assertEquals(SkinText.MAX_SECTIONS, many.size)
        val keys = SkinText.ini("[s]\n" + (0..SkinText.MAX_KEYS + 5).joinToString("") { "k$it=1\n" })
        assertEquals(SkinText.MAX_KEYS, keys.getValue("s").size)
    }

    @Test
    fun coloursAndNumbers() {
        assertEquals(0xFF112233.toInt(), SkinText.color("112233"))
        assertEquals(0x80112233.toInt(), SkinText.color("0x80112233"))
        assertEquals(0x80112233.toInt(), SkinText.color(" #80112233 "))
        assertEquals(0xFFABCDEF.toInt(), SkinText.color("0XabCDef"))
        assertNull(SkinText.color("12345"))
        assertNull(SkinText.color("zz1122"))
        assertNull(SkinText.color(null))
        assertContentEquals(intArrayOf(1, -2, 3, 4), SkinText.ints(" 1, -2,3 ,4,9", 4))
        assertNull(SkinText.ints("1,2,3", 4))
        assertNull(SkinText.ints("1,2,x,4", 4))
        assertNull(SkinText.ints("1,2,3,999999999", 4))
        assertNull(SkinText.ints(null, 1))
    }

    @Test
    fun pixels() {
        val p = Pixels(4, 3)
        p.paste(Pixels.filled(2, 1, 0xFF0000FF.toInt()), 1, 1)
        assertEquals(Pixels.Box(1, 1, 3, 2), p.opaqueBounds())
        assertNull(Pixels(2, 2).opaqueBounds())
        assertTrue(p.translucent())
        assertEquals(0xFF0000FF.toInt(), p.average(keepAlpha = false))
        assertEquals(0x2B0000FF, p.average())
        assertNull(Pixels(2, 2).average())
        assertEquals(0xFF0000FF.toInt(), p.glyphColor())
        assertNull(Pixels(2, 2).glyphColor())
        // a crop held to the image; one wholly out of it none
        assertEquals(2 to 2, p.crop(2, 1, 99, 99)!!.let { it.width to it.height })
        assertNull(p.crop(10, 10, 2, 2))
        assertNull(p.crop(0, 0, 0, 5))
        assertEquals(p, p.crop(-5, -5, Int.MAX_VALUE, Int.MAX_VALUE))
        // a flat band stays flat stretched and shrunk; transparent pixels do not darken the edge
        val r = Pixels.filled(3, 3, 0xFF336699.toInt()).resized(7, 2)
        assertTrue(r.argb.all { it == 0xFF336699.toInt() })
        val half = Pixels(2, 1, intArrayOf(0xFFFFFFFF.toInt(), 0)).resized(1, 1)
        assertEquals(0x80FFFFFF.toInt(), half[0, 0])
        // a nine-patch: corners kept
        val src = Pixels(3, 3, IntArray(9) { if (it == 4) 0xFF00FF00.toInt() else 0xFFFF0000.toInt() })
        val n = src.nine(Pixels.Box(1, 1, 1, 1), 10, 6)
        assertEquals(0xFFFF0000.toInt(), n[0, 0])
        assertEquals(0xFF00FF00.toInt(), n[5, 3])
        assertEquals(0xFFFF0000.toInt(), n[9, 5])
        assertEquals(10 to 6, src.nine(null, 10, 6).let { it.width to it.height })
        // insets past the size: cut to half
        assertEquals(0xFFFF0000.toInt(), src.nine(Pixels.Box(9, 9, 9, 9), 4, 4)[0, 0])
        // insets leaving no middle across (an inner rect outside its source): none across, no band left undrawn
        assertTrue(src.nine(Pixels.Box(3, 0, 0, 0), 8, 3).argb.none { it == 0 })
        // source over: half white over black is grey, transparent over a colour is the colour
        val d = Pixels.filled(2, 1, 0xFF000000.toInt())
        d.over(Pixels(2, 1, intArrayOf(0x80FFFFFF.toInt(), 0)), 0, 0)
        assertEquals(0xFF808080.toInt(), d[0, 0])
        assertEquals(0xFF000000.toInt(), d[1, 0])
        d.over(Pixels.filled(5, 5, 0xFF0000FF.toInt()), 1, -2)
        assertEquals(0xFF0000FF.toInt(), d[1, 0])
        val t = Pixels(2, 1)
        t.over(Pixels(1, 1, intArrayOf(0x80FFFFFF.toInt())), 0, 0)
        assertEquals(0x80FFFFFF.toInt(), t[0, 0])
        // a paste partly out of the image
        val q = Pixels(3, 3)
        q.paste(Pixels.filled(3, 3, -1), -2, 2)
        assertEquals(-1, q[0, 2])
        assertEquals(0, q[1, 2])
        q.paste(Pixels.filled(1, 1, -1), 5, 0)
        assertEquals(2, PixelsImage(q).pixels(1, 1, 2, 2).width)
        assertEquals(1, PixelsImage(q).pixels(9, 9, 2, 2).width)
    }

    @Test
    fun soundsAsAThemeTakesThem() {
        val short = wav(50)
        val (name, bytes) = assertNotNull(SkinSound.convert("res/Click Me!.WAV", short))
        assertEquals("clickme.wav", name)
        assertTrue(SoundFile.check(name, bytes).isEmpty())
        // stereo 48 kHz for 3 s: a second, mono, at most 100 KB
        val long = assertNotNull(SkinSound.convert("x.wav", wav(3000, 48000, 2))).second
        assertTrue(long.size <= SoundFile.MAX_BYTES)
        assertTrue(SoundFile.check("x.wav", long).isEmpty())
        // cut at SoundFile.MAX_MS, not shorter: its frames are its rate's for that long (the header's rate and align)
        fun le(at: Int, n: Int) = (0 until n).sumOf { (long[at + it].toInt() and 0xFF) shl (8 * it) }
        assertEquals(le(24, 4) * SoundFile.MAX_MS / 1000, (long.size - 44) / le(32, 2))
        assertNull(SkinSound.convert("x.flac", short))
        assertNull(SkinSound.convert("x.wav", ByteArray(100)))
        assertEquals("sound.ogg", SkinSound.convert("....ogg", SoundSamples.VORBIS)?.first)
        assertEquals("b.mp3", SkinSound.convert("A/B.mp3", SoundSamples.MP3_PLAIN)?.first)
        assertNull(SkinSound.convert("x.ogg", ByteArray(200)))
    }

    @Test
    fun sameFillsByPixelsAndInsets() {
        // same() decides whether a key's pressed look is written or only tinted: an image with other insets
        // stretches otherwise, so it is another look even of the same pixels
        val px = Pixels.filled(4, 4, -1)
        val copy = Pixels.filled(4, 4, -1)
        val box = Pixels.Box(1, 1, 1, 1)
        val rows = listOf(
            Triple("same pixels (a copy), same insets", SkinFill.Image(px, box) to SkinFill.Image(copy, Pixels.Box(1, 1, 1, 1)), true),
            Triple("same pixels, other insets", SkinFill.Image(px, box) to SkinFill.Image(copy, Pixels.Box(2, 1, 1, 1)), false),
            Triple("same pixels, insets and none", SkinFill.Image(px, box) to SkinFill.Image(px, null), false),
            Triple("one instance", SkinFill.Image(px, box).let { it to it }, true),
            Triple("other pixels", SkinFill.Image(px, null) to SkinFill.Image(Pixels.filled(4, 4, 0), null), false),
            Triple("two of none", null to null, true),
        )
        assertRows(rows, { it.first }) { (why, pair, same) -> assertEquals(same, SkinPatch.same(pair.first, pair.second), why) }
    }

    @Test
    fun aiffBecomesWav() {
        for ((form, comp, bits) in listOf(Triple("AIFF", "NONE", 16), Triple("AIFC", "sowt", 16), Triple("AIFF", "NONE", 8))) {
            val aiff = aiff(form, comp, bits, frames = 2205, rate = 22050)
            val w = assertNotNull(SkinSound.aiffToWav(aiff), "$form $comp $bits")
            assertEquals("RIFF", w.decodeToString(0, 4))
            assertNotNull(SkinSound.convert("tock.aiff", aiff))
        }
        // compressed AIFC, 24-bit, a rate of nothing, a chunk longer than the file
        assertNull(SkinSound.aiffToWav(aiff("AIFC", "ima4", 16, 100, 22050)))
        assertNull(SkinSound.aiffToWav(aiff("AIFF", "NONE", 24, 100, 22050)))
        assertNull(SkinSound.aiffToWav(aiff("AIFF", "NONE", 16, 100, 1)))
        val cut = aiff("AIFF", "NONE", 16, 100, 22050)
        assertNull(SkinSound.aiffToWav(cut.copyOf(cut.size - 10)))
        assertNull(SkinSound.aiffToWav("FORMxxxxWAVE".encodeToByteArray()))
    }

    @Test
    fun soundsFuzzed() {
        val rnd = Random(7)
        val seeds = listOf(wav(40), aiff("AIFF", "NONE", 16, 900, 22050), aiff("AIFC", "sowt", 8, 900, 22050))
        repeat(FUZZ) {
            val b = seeds[it % seeds.size].copyOf()
            repeat(1 + rnd.nextInt(6)) { b[rnd.nextInt(minOf(b.size, 80))] = rnd.nextInt(256).toByte() }
            val cut = b.copyOf(rnd.nextInt(b.size + 1))
            // never throws; what it gives a theme takes
            for (x in listOf(b, cut)) {
                SkinSound.convert("a.wav", x)?.let { (n, out) -> assertTrue(SoundFile.check(n, out).isEmpty()) }
                SkinSound.convert("a.aiff", x)?.let { (n, out) -> assertTrue(SoundFile.check(n, out).isEmpty()) }
            }
        }
    }

    @Test
    fun textFuzzed() {
        val rnd = Random(11)
        repeat(FUZZ) {
            val b = ByteArray(rnd.nextInt(300)) { rnd.nextInt(256).toByte() }
            val s = SkinText.decode(b, legacy)
            SkinText.ini(s)
            SkinText.ints(s, 4)
            SkinText.color(s)
        }
    }

    // an AIFF of a click: [frames] of mono PCM at [rate]
    private fun aiff(form: String, comp: String, bits: Int, frames: Int, rate: Int): ByteArray {
        val data = ByteArray(frames * bits / 8) { (it % 50).toByte() }
        val commSize = if (form == "AIFC") 22 else 18
        val out = ArrayList<Byte>()
        fun tag(s: String) = s.forEach { out += it.code.toByte() }
        fun be(v: Int, n: Int) { for (i in n - 1 downTo 0) out += (v shr (8 * i)).toByte() }
        tag("FORM"); be(4 + 8 + commSize + 8 + 8 + data.size, 4); tag(form)
        tag("COMM"); be(commSize, 4); be(1, 2); be(frames, 4); be(bits, 2)
        // the rate as an 80-bit extended float
        var e = 16383 + 63
        var m = rate.toLong()
        while (m > 0 && m and (1L shl 63) == 0L) { m = m shl 1; e-- }
        be(e, 2); be((m ushr 32).toInt(), 4); be(m.toInt(), 4)
        if (form == "AIFC") tag(comp)
        tag("SSND"); be(8 + data.size, 4); be(0, 4); be(0, 4)
        data.forEach { out += it }
        return out.toByteArray()
    }

    private companion object {
        const val FUZZ = 400
    }
}
