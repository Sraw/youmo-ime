/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.theme.SoundFile
import org.fcitx.fcitx5.android.scene.theme.SoundFile.Format
import org.fcitx.fcitx5.android.scene.theme.SoundFile.Info
import org.fcitx.fcitx5.android.scene.theme.SoundKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 060-theme-sounds-icons §1.2: a theme's key sound, Ogg Vorbis, WAV (8/16-bit PCM) or MP3, checked from its bytes. */
class SoundFileTest {

    private fun ok(name: String, bytes: ByteArray): Info = SoundFile.read(name, bytes).getOr { error("$name: $it") }

    private fun refused(name: String, bytes: ByteArray, part: String) {
        val problems = SoundFile.check(name, bytes)
        assertTrue(problems.size == 1 && part in problems[0].message, "$name: expected one problem with \"$part\", got $problems")
    }

    // ---- Ogg

    @Test
    fun realVorbis() = assertEquals(Info(Format.OGG, 2, 8000, 56), ok("tap.ogg", SoundSamples.VORBIS))

    @Test
    fun opusRefused() = refused("tap.ogg", SoundSamples.OPUS, "Opus")

    @Test
    fun syntheticVorbis() {
        // 4000 samples at 8 kHz: half a second; a packet holding "OggS" is data, the pages walked by their lengths
        val file = vorbis(8000, 1, listOf(-1L, 2000L, 4000L), data = "OggS".encodeToByteArray())
        assertEquals(Info(Format.OGG, 1, 8000, 500), ok("a.ogg", file))
    }

    @Test
    fun oggGranules() {
        refused("a.ogg", vorbis(8000, 1, listOf(-1L, 1L shl 40)), "over 1000 ms")
        refused("a.ogg", vorbis(8000, 1, listOf(-1L, -5L)), "over 1000 ms")
        // the last granule given, not the largest: 8001 samples at 8 kHz is over a second
        refused("a.ogg", vorbis(8000, 1, listOf(-1L, 8001L)), "over 1000 ms")
        assertEquals(1000, ok("a.ogg", vorbis(8000, 1, listOf(-1L, 8000L))).ms)
    }

    @Test
    fun oggRanges() {
        refused("a.ogg", vorbis(0, 1, listOf(-1L, 100L)), "0 Hz")
        refused("a.ogg", vorbis(96000, 2, listOf(-1L, 100L)), "96000 Hz")
        refused("a.ogg", vorbis(8000, 6, listOf(-1L, 100L)), "6 channels")
    }

    @Test
    fun oggStreams() {
        val one = vorbis(8000, 1, listOf(-1L, 100L))
        // a second stream: a page of another serial, and a second BOS page of the same
        refused("a.ogg", one + page(BOS, 100L, serial = 9, seq = 0, body = ByteArray(4)), "more than one Ogg stream")
        refused("a.ogg", one + page(0, 100L, serial = 9, seq = 2, body = ByteArray(4)), "more than one Ogg stream")
        refused("a.ogg", one + page(BOS, 100L, serial = 1, seq = 2, body = ByteArray(4)), "more than one Ogg stream")
    }

    @Test
    fun oggDamage() {
        val real = SoundSamples.VORBIS
        refused("a.ogg", real.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }, "CRC")
        refused("a.ogg", real.copyOf(real.size - 3), "truncated")
        // a page whose segments say more than the file holds
        val one = vorbis(8000, 1, listOf(-1L, 100L))
        refused("a.ogg", one + one.copyOfRange(0, 30), "truncated")
        refused("a.ogg", one.copyOf().also { it[4] = 1 }, "Ogg version 1")
    }

    // ---- WAV

    @Test
    fun wavPcm() {
        assertEquals(Info(Format.WAV, 1, 8000, 100), ok("a.wav", wav(channels = 1, rate = 8000, bits = 8, data = 800)))
        assertEquals(Info(Format.WAV, 2, 44100, 50), ok("a.WAV", wav(channels = 2, rate = 44100, bits = 16, data = 44100 / 20 * 4)))
    }

    @Test
    fun wavEncodingsRefused() {
        refused("a.wav", wav(tag = 3, bits = 32), "only 8- or 16-bit PCM")
        refused("a.wav", wav(tag = 2, bits = 4), "only 8- or 16-bit PCM")
        refused("a.wav", wav(tag = 0xFFFE, bits = 16), "only 8- or 16-bit PCM")
        refused("a.wav", wav(bits = 24), "only 8- or 16-bit PCM")
    }

    @Test
    fun wavHeaderChecked() {
        refused("a.wav", wav(align = 3), "block align")
        refused("a.wav", wav(byteRate = 1), "byte rate")
        refused("a.wav", wav(data = 100).let { it.copyOf(it.size - 10) }, "its data runs past")
        refused("a.wav", wav(dataFirst = true), "data before fmt")
        refused("a.wav", wav(rate = 4000), "4000 Hz")
    }

    @Test
    fun wavChunks() {
        // an odd chunk padded with a byte, then data found where the padding puts it
        assertEquals(100, ok("a.wav", wav(extra = listOf(3), data = 1600)).ms)
        // 64 chunks walked: fmt, 62 others, data; one more is too many
        assertEquals(100, ok("a.wav", wav(extra = List(62) { 2 }, data = 1600)).ms)
        refused("a.wav", wav(extra = List(63) { 2 }, data = 1600), "over 64 chunks")
    }

    // ---- MP3

    @Test
    fun realMp3s() {
        // lame's Info frame counted as a frame: its frame count is never taken
        val xing = ok("a.mp3", SoundSamples.MP3_XING)
        assertEquals(Format.MP3, xing.format)
        assertEquals(2, xing.channels)
        assertEquals(44100, xing.rate)
        val plain = ok("a.mp3", SoundSamples.MP3_PLAIN)
        assertEquals(1, plain.channels)
        assertEquals(22050, plain.rate)
    }

    @Test
    fun framesCounted() {
        // 10 MPEG-1 frames at 44.1 kHz: 11520 samples, 262 ms (rounded up)
        assertEquals(Info(Format.MP3, 2, 44100, 262), ok("a.mp3", frames(MPEG1, 10)))
        assertEquals(Info(Format.MP3, 1, 44100, 262), ok("a.mp3", frames(MPEG1_MONO, 10)))
        // an ID3v2 skipped, then frames; a footer flag adds ten bytes
        assertEquals(262, ok("a.mp3", id3(20) + frames(MPEG1, 10)).ms)
        assertEquals(262, ok("a.mp3", id3(20, footer = true) + frames(MPEG1, 10)).ms)
        // MPEG-2 at 22.05 kHz: 576 samples a frame
        assertEquals(Info(Format.MP3, 2, 22050, 262), ok("a.mp3", frames(MPEG2, 10)))
    }

    @Test
    fun tagsNeverTrusted() {
        // an Xing and a VBRI frame each saying one frame, 39 frames after: every frame counted, over a second
        val xing = frames(MPEG1, 1).also { "Xing".encodeToByteArray().copyInto(it, 4 + 32); it[4 + 32 + 7] = 1; it[4 + 32 + 11] = 1 }
        refused("a.mp3", xing + frames(MPEG1, 39), "over 1000 ms")
        val vbri = frames(MPEG1, 1).also { "VBRI".encodeToByteArray().copyInto(it, 4 + 32); it[4 + 32 + 17] = 1 }
        refused("a.mp3", vbri + frames(MPEG1, 39), "over 1000 ms")
    }

    @Test
    fun framesRefused() {
        refused("a.mp3", frames(LAYER_II, 2), "not Layer III")
        refused("a.mp3", frames(FREE, 2), "free-format")
        refused("a.mp3", frames(RESERVED, 2), "reserved")
        refused("a.mp3", frames(MPEG1, 2) + frames(MPEG1_48K, 2), "change version or sample rate")
        refused("a.mp3", frames(MPEG1, 2) + frames(MPEG2, 2), "change version or sample rate")
        refused("a.mp3", frames(MPEG1, 2).let { it.copyOf(it.size - 1) }, "truncated")
        // no search for the first frame after the ID3v2
        refused("a.mp3", id3(20) + ByteArray(1) + frames(MPEG1, 2), "no MPEG frame")
        // MPEG-2 at 24 kHz, 8 kb/s: 24-byte frames, 4097 of them under 100 KB
        refused("a.mp3", frames(MPEG2_SMALL, 4097), "over 4096 frames")
    }

    @Test
    fun mp3Tail() {
        val f = frames(MPEG1, 2)
        assertEquals(53, ok("a.mp3", f + id3v1()).ms)
        assertEquals(53, ok("a.mp3", f + ape(header = true)).ms)
        assertEquals(53, ok("a.mp3", f + ape(header = false)).ms)
        assertEquals(53, ok("a.mp3", f + ape(header = true) + id3v1()).ms)
        refused("a.mp3", f + "garbage!".encodeToByteArray(), "unknown data after its frames")
        refused("a.mp3", f + ByteArray(200), "unknown data after its frames")
    }

    // ---- all three

    @Test
    fun shared() {
        refused("a.mp3", wav(), "its extension and its content differ")
        refused("a.ogg", frames(MPEG1, 2), "its extension and its content differ")
        refused("a.flac", wav(), "not .ogg, .wav or .mp3")
        refused("a.wav", "hello".encodeToByteArray(), "not an Ogg, WAV or MP3")
        refused("a.wav", ByteArray(SoundFile.MAX_BYTES + 1), "over 100 KB")
        assertEquals(1000, ok("a.wav", wav(rate = 8000, bits = 8, channels = 1, data = 8000)).ms)
        refused("a.wav", wav(rate = 8000, bits = 8, channels = 1, data = 8008), "over 1000 ms long (1001 ms)")
    }

    @Test
    fun soundKeys() {
        assertEquals(SoundKey.SPACE, SoundKey.of("space", "function"))
        assertEquals(SoundKey.BACKSPACE, SoundKey.of("delete", "function"))
        assertEquals(SoundKey.ENTER, SoundKey.of("return", "letter"))
        assertEquals(SoundKey.ENTER, SoundKey.of("standard", "enter"))
        assertEquals(SoundKey.SPACE, SoundKey.of("standard", "space"))
        assertEquals(SoundKey.FUNCTION, SoundKey.of("standard", "function"))
        assertEquals(SoundKey.LETTER, SoundKey.of("standard", "letter"))
        assertEquals(SoundKey.LETTER, SoundKey.of("standard", "anything"))
        assertEquals(SoundKey.BACKSPACE, SoundKey.of("backspace"))
        assertEquals(null, SoundKey.of("future"))
    }

    // ---- files made here

    private fun le(v: Long, n: Int) = ByteArray(n) { (v ushr (8 * it)).toByte() }

    // Ogg's CRC bit by bit (not SoundFile's table)
    private fun oggCrc(b: ByteArray): Int {
        var c = 0
        for (x in b) {
            c = c xor ((x.toInt() and 0xff) shl 24)
            repeat(8) { c = if (c < 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
        }
        return c
    }

    private fun page(type: Int, granule: Long, serial: Int = 1, seq: Int, body: ByteArray): ByteArray {
        val lacing = ArrayList<Byte>()
        var left = body.size
        while (left >= 255) { lacing += 255.toByte(); left -= 255 }
        lacing += left.toByte()
        val head = "OggS".encodeToByteArray() + byteArrayOf(0, type.toByte()) + le(granule, 8) + le(serial.toLong(), 4) +
            le(seq.toLong(), 4) + ByteArray(4) + byteArrayOf(lacing.size.toByte()) + lacing.toByteArray()
        val p = head + body
        le(oggCrc(p).toLong(), 4).copyInto(p, 22)
        return p
    }

    // an identification page, then a page of each granule (the first the comment and setup's, -1)
    private fun vorbis(rate: Int, channels: Int, granules: List<Long>, data: ByteArray = ByteArray(8)): ByteArray {
        val id = byteArrayOf(1) + "vorbis".encodeToByteArray() + le(0, 4) + byteArrayOf(channels.toByte()) + le(rate.toLong(), 4) + ByteArray(13) +
            byteArrayOf(1)
        var file = page(BOS, 0, seq = 0, body = id)
        granules.forEachIndexed { i, g -> file += page(0, g, seq = i + 1, body = data) }
        return file
    }

    private fun chunk(id: String, body: ByteArray) = id.encodeToByteArray() + le(body.size.toLong(), 4) + body + ByteArray(body.size % 2)

    @Suppress("LongParameterList")
    private fun wav(
        tag: Int = 1, channels: Int = 1, rate: Int = 16000, bits: Int = 8, align: Int = channels * bits / 8,
        byteRate: Int = rate * align, data: Int = 160, extra: List<Int> = emptyList(), dataFirst: Boolean = false,
    ): ByteArray {
        val fmt = chunk("fmt ", le(tag.toLong(), 2) + le(channels.toLong(), 2) + le(rate.toLong(), 4) + le(byteRate.toLong(), 4) + le(align.toLong(), 2) + le(bits.toLong(), 2))
        val others = extra.fold(ByteArray(0)) { acc, n -> acc + chunk("LIST", ByteArray(n)) }
        val d = chunk("data", ByteArray(data))
        val body = "WAVE".encodeToByteArray() + if (dataFirst) d + fmt else fmt + others + d
        return "RIFF".encodeToByteArray() + le(body.size.toLong(), 4) + body
    }

    // [n] frames of [header] (its length worked out here), zeros after each header
    private fun frames(header: Int, n: Int): ByteArray {
        val h = ByteArray(4) { (header ushr (24 - 8 * it)).toByte() }
        val length = FRAME_LENGTHS[header] ?: 64
        val out = ByteArray(length * n)
        repeat(n) { h.copyInto(out, it * length) }
        return out
    }

    private fun id3(size: Int, footer: Boolean = false): ByteArray =
        "ID3".encodeToByteArray() + byteArrayOf(4, 0, if (footer) 0x10 else 0, 0, 0, 0, size.toByte()) + ByteArray(size) + ByteArray(if (footer) 10 else 0)

    private fun id3v1() = "TAG".encodeToByteArray() + ByteArray(125)

    private fun ape(header: Boolean): ByteArray {
        val items = ByteArray(20)
        fun part(flags: Long) = "APETAGEX".encodeToByteArray() + le(2000, 4) + le(items.size + 32L, 4) + le(1, 4) + le(flags, 4) + ByteArray(8)
        val hasHeader = if (header) 0x80000000L else 0L
        return (if (header) part(hasHeader or 0x20000000L) else ByteArray(0)) + items + part(hasHeader)
    }

    private companion object {
        const val BOS = 2
        const val MPEG1 = 0xFFFB9000.toInt() // MPEG-1 Layer III, 128 kb/s, 44.1 kHz, stereo: 417 bytes
        const val MPEG1_MONO = 0xFFFB90C0.toInt()
        const val MPEG1_48K = 0xFFFB9400.toInt() // 48 kHz: 384 bytes
        const val MPEG2 = 0xFFF31000.toInt() // MPEG-2 Layer III, 8 kb/s, 22.05 kHz: 26 bytes
        const val MPEG2_SMALL = 0xFFF31400.toInt() // MPEG-2, 8 kb/s, 24 kHz: 24 bytes
        const val LAYER_II = 0xFFFD9000.toInt()
        const val FREE = 0xFFFB0000.toInt()
        const val RESERVED = 0xFFEB9000.toInt()
        val FRAME_LENGTHS = mapOf(MPEG1 to 417, MPEG1_MONO to 417, MPEG1_48K to 384, MPEG2 to 26, MPEG2_SMALL to 24)
    }
}
