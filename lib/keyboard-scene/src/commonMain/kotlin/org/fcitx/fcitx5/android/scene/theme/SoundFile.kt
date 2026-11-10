/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Problem

/**
 * A theme's key sound (060-theme-sounds-icons §1.2): Ogg Vorbis, WAV of 8- or 16-bit PCM, or MP3 (MPEG
 * Layer III), each at most [MAX_BYTES], [MAX_MS] long, of [MIN_CHANNELS]–[MAX_CHANNELS] channels at
 * [MIN_RATE]–[MAX_RATE] Hz, its extension its content's. One check for the app's import, its loading
 * (a pack restored from a backup was never imported) and the theme maker.
 *
 * The file comes from anywhere: every length and offset is held to the bytes left before it is read, a
 * value past [Int] is over the limit, a rate is in range before it divides. The length is the headers'
 * (an Ogg's last granule, a WAV's data, an MP3's frames counted); what the platform decodes is bounded
 * besides by the file's size and its own heap limit on one sample, which a file lying about its length
 * meets: it fails to load and the system's click plays.
 */
object SoundFile {
    const val MAX_BYTES = 100 * 1024
    const val MAX_MS = 1000
    const val MIN_CHANNELS = 1
    const val MAX_CHANNELS = 2
    const val MIN_RATE = 8000
    const val MAX_RATE = 48000

    /** `<sound>`s in a theme at most: a class a file, and room for classes of later versions. */
    const val MAX_SOUNDS = 16

    /** The extensions taken, each its format's. */
    val EXTENSIONS = mapOf("ogg" to Format.OGG, "wav" to Format.WAV, "mp3" to Format.MP3)

    enum class Format { OGG, WAV, MP3 }

    /** What a sound is: its [format], [channels], [rate] in Hz, [ms] its length rounded up. */
    data class Info(val format: Format, val channels: Int, val rate: Int, val ms: Int)

    /** What is wrong with [bytes] as the sound file [name]; none: it is one. */
    fun check(name: String, bytes: ByteArray): List<Problem> = read(name, bytes).problems()

    /** [bytes], the file [name], read: what it is, or the first thing wrong with it. */
    @Suppress("TooGenericExceptionCaught")
    fun read(name: String, bytes: ByteArray): Parsed<Info> = try {
        Parsed.Ok(info(name, bytes))
    } catch (e: Bad) {
        Parsed.Failed(0, "$name: ${e.message}")
    } catch (e: Throwable) {
        // a bug of ours a file finds is that file's problem, never a crash
        Parsed.Failed(0, "$name: could not be read (${e::class.simpleName})")
    }

    private class Bad(message: String) : Exception(message)

    private fun bad(message: String): Nothing = throw Bad(message)

    private fun info(name: String, b: ByteArray): Info {
        if (b.size > MAX_BYTES) bad("over ${MAX_BYTES / KB} KB")
        val byName = EXTENSIONS[name.substringAfterLast('.', "").lowercase()] ?: bad("not .ogg, .wav or .mp3")
        val byContent = sniff(b) ?: bad("not an Ogg, WAV or MP3 file")
        if (byName != byContent) bad("its extension and its content differ (it is ${byContent.name.lowercase()})")
        val (channels, rate, samples) = when (byContent) {
            Format.OGG -> ogg(b)
            Format.WAV -> wav(b)
            Format.MP3 -> mp3(b)
        }
        val ms = (samples * MS_PER_S + rate - 1) / rate
        if (ms > MAX_MS) bad("over $MAX_MS ms long ($ms ms)")
        return Info(byContent, channels, rate, ms.toInt())
    }

    private fun sniff(b: ByteArray): Format? = when {
        b.startsWith(0, "OggS") -> Format.OGG
        b.startsWith(0, "RIFF") && b.startsWith(RIFF_FORM, "WAVE") -> Format.WAV
        b.startsWith(0, "ID3") || (b.size >= 2 && b.u8(0) == SYNC_BYTE && b.u8(1) and SYNC_LOW == SYNC_LOW) -> Format.MP3
        else -> null
    }

    // channels and rate in range, checked before anything divides by the rate
    private fun ranged(channels: Int, rate: Long) {
        if (channels !in MIN_CHANNELS..MAX_CHANNELS) bad("$channels channels: $MIN_CHANNELS or $MAX_CHANNELS taken")
        if (rate !in MIN_RATE..MAX_RATE) bad("$rate Hz: $MIN_RATE–$MAX_RATE Hz taken")
    }

    private data class Audio(val channels: Int, val rate: Int, val samples: Long)

    // ---- Ogg Vorbis: every page walked, its CRC checked; one logical stream, Vorbis's

    private fun ogg(b: ByteArray): Audio {
        var at = 0
        var serial = 0L
        var channels = 0
        var rate = 0
        var granule = -1L
        var pages = 0
        while (at < b.size) {
            if (b.size - at < OGG_HEADER || !b.startsWith(at, "OggS")) bad("truncated or not an Ogg page at byte $at")
            if (b.u8(at + OGG_VERSION) != 0) bad("Ogg version ${b.u8(at + OGG_VERSION)}")
            val type = b.u8(at + OGG_TYPE)
            val segments = b.u8(at + OGG_SEGMENTS)
            if (b.size - at - OGG_HEADER < segments) bad("truncated Ogg page at byte $at")
            var body = 0
            for (i in 0 until segments) body += b.u8(at + OGG_HEADER + i)
            val length = OGG_HEADER + segments + body
            if (b.size - at < length) bad("truncated Ogg page at byte $at")
            if (crc(b, at, length) != b.u32(at + OGG_CRC)) bad("Ogg page at byte $at: CRC does not match")
            val bos = type and OGG_BOS != 0
            if (bos != (pages == 0)) bad("more than one Ogg stream")
            val pageSerial = b.u32(at + OGG_SERIAL)
            if (pages == 0) {
                serial = pageSerial
                val id = vorbisId(b, at + OGG_HEADER + segments, body)
                channels = id.channels
                rate = id.rate
            } else if (pageSerial != serial) {
                bad("more than one Ogg stream")
            }
            val g = b.i64(at + OGG_GRANULE)
            if (g != -1L) {
                if (g < 0 || g > Int.MAX_VALUE) bad("over $MAX_MS ms long (granule $g)")
                granule = g
            }
            pages++
            at += length
        }
        if (granule < 0) bad("no audio in the Ogg stream")
        return Audio(channels, rate, granule)
    }

    // the first packet, on the first page alone: Vorbis's identification header
    private fun vorbisId(b: ByteArray, first: Int, body: Int): Audio {
        if (body >= OPUS_HEAD.length && b.startsWith(first, OPUS_HEAD)) bad("Ogg Opus is not taken: Ogg Vorbis, WAV or MP3")
        if (body < VORBIS_ID_LENGTH || b.u8(first) != 1 || !b.startsWith(first + 1, "vorbis")) bad("not Ogg Vorbis")
        if (b.u32(first + VORBIS_VERSION) != 0L) bad("Vorbis version ${b.u32(first + VORBIS_VERSION)}")
        val channels = b.u8(first + VORBIS_CHANNELS)
        val rate = b.u32(first + VORBIS_RATE)
        ranged(channels, rate)
        return Audio(channels, rate.toInt(), 0)
    }

    // Ogg's CRC-32: polynomial 0x04C11DB7, not reflected, nothing in or out, the page's own CRC as zeros
    private fun crc(b: ByteArray, at: Int, length: Int): Long {
        var c = 0
        for (i in 0 until length) {
            val byte = if (i - OGG_CRC in 0 until CRC_BYTES) 0 else b.u8(at + i)
            c = (c shl BYTE_BITS) xor CRC_TABLE[((c ushr CRC_TOP) xor byte) and BYTE_MASK]
        }
        return c.toLong() and U32_MASK
    }

    private val CRC_TABLE = IntArray(BYTE_VALUES) { n ->
        var r = n shl CRC_TOP
        repeat(BYTE_BITS) { r = if (r and Int.MIN_VALUE != 0) (r shl 1) xor CRC_POLY else r shl 1 }
        r
    }

    // ---- WAV: RIFF chunks walked, `fmt ` (PCM, 8 or 16 bits) before `data`

    private fun wav(b: ByteArray): Audio {
        var at = WAV_FIRST_CHUNK
        var fmt: Audio? = null
        var blockAlign = 0
        var chunks = 0
        while (true) {
            if (b.size - at < CHUNK_HEADER) bad(if (fmt == null) "no fmt chunk" else "no data chunk")
            if (++chunks > MAX_CHUNKS) bad("over $MAX_CHUNKS chunks")
            val id = b.ascii(at, ID_LENGTH)
            val size = b.u32(at + ID_LENGTH)
            val left = (b.size - at - CHUNK_HEADER).toLong()
            if (size > left) bad(if (id == "data") "truncated: its data runs past the file's end" else "truncated: chunk $id runs past the file's end")
            val body = at + CHUNK_HEADER
            when (id) {
                "fmt " -> {
                    if (size < FMT_LENGTH) bad("fmt chunk too short")
                    val tag = b.u16(body)
                    val channels = b.u16(body + FMT_CHANNELS)
                    val rate = b.u32(body + FMT_RATE)
                    val byteRate = b.u32(body + FMT_BYTE_RATE)
                    val align = b.u16(body + FMT_ALIGN)
                    val bits = b.u16(body + FMT_BITS)
                    if (tag != PCM || (bits != BITS_8 && bits != BITS_16)) bad("only 8- or 16-bit PCM is taken")
                    ranged(channels, rate)
                    if (align != channels * bits / BYTE_BITS) bad("block align $align is not channels × bytes")
                    if (byteRate != rate * align) bad("byte rate $byteRate is not rate × block align")
                    fmt = Audio(channels, rate.toInt(), 0)
                    blockAlign = align
                }
                "data" -> {
                    val f = fmt ?: bad("data before fmt")
                    return f.copy(samples = size / blockAlign)
                }
            }
            // a chunk of odd length is padded with a byte
            at = body + size.toInt() + (size and 1L).toInt()
        }
    }

    // ---- MP3: MPEG-1, 2 or 2.5 Layer III frames from the start (after an ID3v2), every one counted

    private class Frame(val version: Int, val rate: Int, val channels: Int, val length: Int, val samples: Int)

    private fun mp3(b: ByteArray): Audio {
        var at = 0
        if (b.startsWith(0, "ID3")) {
            if (b.size < ID3_HEADER) bad("truncated ID3v2")
            var size = 0L
            for (i in 0 until ID3_SIZE_BYTES) {
                val v = b.u8(ID3_SIZE + i)
                if (v and SYNC_SAFE_BIT != 0) bad("ID3v2's size is not sync-safe")
                size = (size shl SYNC_SAFE_BITS) or v.toLong()
            }
            val footer = if (b.u8(ID3_FLAGS) and ID3_FOOTER != 0) ID3_HEADER else 0
            val end = ID3_HEADER + size + footer
            if (end > b.size) bad("truncated ID3v2")
            at = end.toInt()
        }
        // no search for a sync word: the first frame right there, the platform's extractor would find frames further on
        val first = frame(b, at) ?: bad("no MPEG frame at byte $at")
        var frames = 0L
        while (true) {
            val f = frame(b, at) ?: break
            if (f.version != first.version || f.rate != first.rate) bad("its frames change version or sample rate at byte $at")
            if (b.size - at < f.length) bad("truncated: its last frame runs past the file's end")
            if (++frames > MAX_FRAMES) bad("over $MAX_FRAMES frames")
            at += f.length
        }
        tail(b, at)
        // every frame counted, an Xing/Info/VBRI header's own frame too: what such a header says it holds is not trusted
        return Audio(first.channels, first.rate, frames * first.samples)
    }

    // the frame whose header is at [at]; null where none starts there; refused where one does but is not taken
    private fun frame(b: ByteArray, at: Int): Frame? {
        if (b.size - at < MP3_HEADER || b.u8(at) != SYNC_BYTE || b.u8(at + 1) and SYNC_LOW != SYNC_LOW) return null
        val h1 = b.u8(at + 1)
        val h2 = b.u8(at + 2)
        val h3 = b.u8(at + MP3_MODE_BYTE)
        val version = (h1 shr VERSION_SHIFT) and TWO_BITS
        if (version == MPEG_RESERVED) bad("MPEG version reserved at byte $at")
        if ((h1 shr LAYER_SHIFT) and TWO_BITS != LAYER_III) bad("not Layer III (MP3) at byte $at")
        val bitrateIndex = h2 shr BITRATE_SHIFT
        if (bitrateIndex == 0) bad("free-format bit rate at byte $at")
        if (bitrateIndex == BAD_BITRATE) bad("bad bit rate at byte $at")
        val rateIndex = (h2 shr RATE_SHIFT) and TWO_BITS
        if (rateIndex == BAD_RATE) bad("bad sample rate at byte $at")
        val mpeg1 = version == MPEG_1
        val rate = MPEG1_RATES[rateIndex] shr (if (mpeg1) 0 else if (version == MPEG_2) 1 else 2)
        val kbps = (if (mpeg1) MPEG1_KBPS else MPEG2_KBPS)[bitrateIndex - 1]
        val padding = (h2 shr 1) and 1
        val length = (if (mpeg1) MPEG1_FACTOR else MPEG2_FACTOR) * kbps / rate + padding
        val channels = if ((h3 shr MODE_SHIFT) == MONO) 1 else 2
        return Frame(version, rate, channels, length, if (mpeg1) MPEG1_SAMPLES else MPEG2_SAMPLES)
    }

    // what follows the frames: nothing, an APEv2 tag, an ID3v1 tag, or the APE then the ID3v1
    private fun tail(b: ByteArray, at: Int) {
        var end = b.size
        if (end - at >= ID3V1 && b.startsWith(end - ID3V1, "TAG")) end -= ID3V1
        if (end - at >= APE_FOOTER && b.startsWith(end - APE_FOOTER, "APETAGEX")) {
            val footer = end - APE_FOOTER
            val size = b.u32(footer + APE_SIZE)
            val header = if (b.u32(footer + APE_FLAGS) and APE_HAS_HEADER != 0L) APE_FOOTER.toLong() else 0L
            if (size + header == (end - at).toLong()) end = at
        }
        if (end != at) bad("unknown data after its frames at byte $at")
    }

    // ---- bytes

    private fun ByteArray.u8(at: Int): Int = this[at].toInt() and BYTE_MASK

    private fun ByteArray.u16(at: Int): Int = u8(at) or (u8(at + 1) shl BYTE_BITS)

    private fun ByteArray.u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl SHORT_BITS)

    private fun ByteArray.i64(at: Int): Long = u32(at) or (u32(at + U32_BYTES) shl INT_BITS)

    private fun ByteArray.startsWith(at: Int, s: String): Boolean = at >= 0 && size - at >= s.length && s.indices.all { u8(at + it) == s[it].code }

    private fun ByteArray.ascii(at: Int, n: Int): String = (0 until n).map { u8(at + it).toChar() }.joinToString("")

    private const val KB = 1024
    private const val MS_PER_S = 1000L
    private const val BYTE_MASK = 0xff
    private const val BYTE_BITS = 8
    private const val BYTE_VALUES = 256
    private const val SHORT_BITS = 16
    private const val INT_BITS = 32
    private const val U32_BYTES = 4
    private const val U32_MASK = 0xffffffffL

    private const val OGG_HEADER = 27
    private const val OGG_VERSION = 4
    private const val OGG_TYPE = 5
    private const val OGG_GRANULE = 6
    private const val OGG_SERIAL = 14
    private const val OGG_CRC = 22
    private const val OGG_SEGMENTS = 26
    private const val OGG_BOS = 2
    private const val CRC_BYTES = 4
    private const val CRC_TOP = 24
    private const val CRC_POLY = 0x04C11DB7
    private const val OPUS_HEAD = "OpusHead"
    private const val VORBIS_ID_LENGTH = 30
    private const val VORBIS_VERSION = 7
    private const val VORBIS_CHANNELS = 11
    private const val VORBIS_RATE = 12

    private const val RIFF_FORM = 8
    private const val WAV_FIRST_CHUNK = 12
    private const val CHUNK_HEADER = 8
    private const val ID_LENGTH = 4
    private const val MAX_CHUNKS = 64
    private const val FMT_LENGTH = 16
    private const val FMT_CHANNELS = 2
    private const val FMT_RATE = 4
    private const val FMT_BYTE_RATE = 8
    private const val FMT_ALIGN = 12
    private const val FMT_BITS = 14
    private const val PCM = 1
    private const val BITS_8 = 8
    private const val BITS_16 = 16

    private const val ID3_HEADER = 10
    private const val ID3_FLAGS = 5
    private const val ID3_SIZE = 6
    private const val ID3_SIZE_BYTES = 4
    private const val ID3_FOOTER = 0x10
    private const val SYNC_SAFE_BIT = 0x80
    private const val SYNC_SAFE_BITS = 7
    private const val ID3V1 = 128
    private const val APE_FOOTER = 32
    private const val APE_SIZE = 12
    private const val APE_FLAGS = 20
    private const val APE_HAS_HEADER = 0x80000000L

    private const val MP3_HEADER = 4
    private const val MP3_MODE_BYTE = 3
    private const val SYNC_BYTE = 0xff
    private const val SYNC_LOW = 0xe0
    private const val TWO_BITS = 3
    private const val VERSION_SHIFT = 3
    private const val LAYER_SHIFT = 1
    private const val LAYER_III = 1
    private const val MPEG_RESERVED = 1
    private const val MPEG_2 = 2
    private const val MPEG_1 = 3
    private const val BITRATE_SHIFT = 4
    private const val BAD_BITRATE = 15
    private const val RATE_SHIFT = 2
    private const val BAD_RATE = 3
    private const val MODE_SHIFT = 6
    private const val MONO = 3
    private const val MPEG1_FACTOR = 144_000
    private const val MPEG2_FACTOR = 72_000
    private const val MPEG1_SAMPLES = 1152
    private const val MPEG2_SAMPLES = 576
    private const val MAX_FRAMES = 4096
    private val MPEG1_RATES = intArrayOf(44100, 48000, 32000)
    private val MPEG1_KBPS = intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MPEG2_KBPS = intArrayOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
}
