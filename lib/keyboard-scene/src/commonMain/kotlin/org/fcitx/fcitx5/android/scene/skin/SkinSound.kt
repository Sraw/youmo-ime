/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.theme.SoundFile
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * A skin's key sound made one a theme takes ([SoundFile]): Baidu's iOS skins click in AIFF, which becomes WAV; a
 * WAV of 8- or 16-bit PCM is cut to [SoundFile.MAX_MS], then made mono and halved in rate till it is within
 * [SoundFile.MAX_BYTES]; an Ogg or MP3 is taken as it is where [SoundFile] takes it. Anything else, or a file that
 * lies about its lengths, is none: every length is held to the bytes there before it is read.
 */
object SkinSound {
    /** (file name, bytes) of [name]'s sound [bytes] as a theme carries it, or null. */
    fun convert(name: String, bytes: ByteArray): Pair<String, ByteArray>? {
        val base = name.substringAfterLast('/').substringBeforeLast('.').lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .ifEmpty { "sound" }.take(MAX_NAME)
        val ext = name.substringAfterLast('.', "").lowercase()
        val out = when (ext) {
            "aif", "aiff", "aifc" -> aiffToWav(bytes)?.let(::fitWav)?.let { "$base.wav" to it }
            "wav" -> fitWav(bytes)?.let { "$base.wav" to it }
            "ogg", "mp3" -> "$base.$ext" to bytes
            else -> null
        } ?: return null
        return out.takeIf { SoundFile.check(out.first, out.second).isEmpty() }
    }

    /** AIFF or AIFF-C (`NONE`, `sowt`) of 8- or 16-bit PCM as WAV; anything else null. */
    // a header's every field checked against the bytes there, a line each: one function to read top to bottom
    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")
    fun aiffToWav(b: ByteArray): ByteArray? {
        if (b.size < AIFF_HEADER || tag(b, 0) != "FORM") return null
        val form = tag(b, FORM_TYPE)
        if (form != "AIFF" && form != "AIFC") return null
        var pos = AIFF_HEADER
        var comm: Int = -1
        var commSize = 0
        var data = -1
        var dataSize = 0
        while (pos + CHUNK_HEAD <= b.size) {
            val size = be32(b, pos + TAG)
            if (size < 0 || size > b.size - pos - CHUNK_HEAD) return null
            when (tag(b, pos)) {
                "COMM" -> { comm = pos + CHUNK_HEAD; commSize = size }
                "SSND" -> {
                    if (size < SSND_HEAD) return null
                    val offset = be32(b, pos + CHUNK_HEAD)
                    if (offset < 0 || offset > size - SSND_HEAD) return null
                    data = pos + CHUNK_HEAD + SSND_HEAD + offset
                    dataSize = size - SSND_HEAD - offset
                }
            }
            pos += CHUNK_HEAD + size + (size and 1)
        }
        if (comm < 0 || data < 0 || commSize < COMM_SIZE) return null
        val channels = be16(b, comm)
        val frames = be32(b, comm + 2)
        val bits = be16(b, comm + COMM_BITS)
        val rate = extended(b, comm + COMM_RATE)
        val compression = if (form == "AIFC" && commSize >= COMM_SIZE + TAG) tag(b, comm + COMM_SIZE) else "NONE"
        if (bits != BITS_8 && bits != BITS_16 || compression != "NONE" && compression != "sowt") return null
        if (channels !in 1..2 || frames < 0 || rate !in SoundFile.MIN_RATE.toLong()..MAX_SOURCE_RATE) return null
        val n = minOf(dataSize.toLong(), frames.toLong() * channels * bits / BITS_8).toInt()
        val pcm = b.copyOfRange(data, data + n)
        if (bits == BITS_16 && compression == "NONE") {
            for (i in 0 until n - 1 step 2) { val t = pcm[i]; pcm[i] = pcm[i + 1]; pcm[i + 1] = t }
        } else if (bits == BITS_8) {
            for (i in pcm.indices) pcm[i] = (pcm[i] + SIGN).toByte()   // AIFF's 8-bit is signed, WAV's unsigned
        }
        return wav(pcm, channels, rate.toInt(), bits)
    }

    /** A PCM WAV cut to a second, then mono, then at half the rate, till it fits; 16-bit; null where it is no PCM WAV. */
    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")
    fun fitWav(b: ByteArray): ByteArray? {
        if (b.size < WAV_HEADER || tag(b, 0) != "RIFF" || tag(b, FORM_TYPE) != "WAVE") return null
        var pos = WAV_HEADER
        var fmt = -1
        var data = -1
        var dataSize = 0
        while (pos + CHUNK_HEAD <= b.size) {
            val size = le32(b, pos + TAG)
            if (size < 0) return null
            val body = pos + CHUNK_HEAD
            val have = minOf(size, b.size - body)
            when (tag(b, pos)) {
                "fmt " -> if (have >= FMT_SIZE) fmt = body
                "data" -> { data = body; dataSize = have }
            }
            if (size > b.size - body) break
            pos = body + size + (size and 1)
        }
        if (fmt < 0 || data < 0 || le16(b, fmt) != PCM) return null
        var channels = le16(b, fmt + 2)
        var rate = le32(b, fmt + FMT_RATE)
        val bits = le16(b, fmt + FMT_BITS)
        if (channels !in 1..2 || bits != BITS_8 && bits != BITS_16 || rate !in SoundFile.MIN_RATE..MAX_SOURCE_RATE.toInt()) return null
        val frameBytes = channels * bits / BITS_8
        val n = minOf(dataSize, rate * frameBytes) / frameBytes * frameBytes
        var s = IntArray(n / (bits / BITS_8)) { i ->
            if (bits == BITS_8) ((b[data + i].toInt() and BYTE) - SIGN) shl BITS_8 else le16(b, data + 2 * i).toShort().toInt()
        }
        while (s.size * 2 + WAV_HEADER_OUT > SoundFile.MAX_BYTES || rate > SoundFile.MAX_RATE) {
            s = IntArray(s.size / 2) { (s[2 * it] + s[2 * it + 1]) / 2 }
            if (channels == 2) channels = 1 else rate /= 2
            if (rate < SoundFile.MIN_RATE) return null
        }
        val pcm = ByteArray(s.size * 2)
        for (i in s.indices) { pcm[2 * i] = s[i].toByte(); pcm[2 * i + 1] = (s[i] shr BITS_8).toByte() }
        return wav(pcm, channels, rate, BITS_16)
    }

    private fun wav(pcm: ByteArray, channels: Int, rate: Int, bits: Int): ByteArray {
        val out = ByteArray(WAV_HEADER_OUT + pcm.size)
        fun put(at: Int, s: String) = s.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }
        fun le(at: Int, v: Int, n: Int) { for (i in 0 until n) out[at + i] = (v shr (BITS_8 * i)).toByte() }
        put(0, "RIFF"); le(TAG, WAV_HEADER_OUT - CHUNK_HEAD + pcm.size, 4); put(FORM_TYPE, "WAVE")
        put(WAV_HEADER, "fmt "); le(WAV_HEADER + TAG, FMT_SIZE, 4)
        le(FMT_AT, PCM, 2); le(FMT_AT + 2, channels, 2); le(FMT_AT + FMT_RATE, rate, 4)
        le(FMT_AT + FMT_BYTE_RATE, rate * channels * bits / BITS_8, 4); le(FMT_AT + FMT_ALIGN, channels * bits / BITS_8, 2)
        le(FMT_AT + FMT_BITS, bits, 2)
        put(DATA_AT, "data"); le(DATA_AT + TAG, pcm.size, 4)
        pcm.copyInto(out, WAV_HEADER_OUT)
        return out
    }

    // an 80-bit IEEE extended float (AIFF's sample rate), as a whole number; -1 where it is none in range
    private fun extended(b: ByteArray, at: Int): Long {
        val exp = be16(b, at) and EXP_MASK
        var mant = 0L
        for (i in 0 until MANTISSA) mant = mant shl BITS_8 or (b[at + 2 + i].toLong() and BYTE.toLong())
        val shift = exp - EXP_BIAS - MANT_BITS
        val value = (mant ushr 1).toDouble() * 2.0 * 2.0.pow(shift)
        return if (value.isFinite() && value < MAX_SOURCE_RATE * 2) value.roundToLong() else -1
    }

    private fun tag(b: ByteArray, at: Int) = if (at + TAG > b.size) "" else CharArray(TAG) { (b[at + it].toInt() and BYTE).toChar() }.concatToString()
    private fun be16(b: ByteArray, at: Int) = (b[at].toInt() and BYTE shl BITS_8) or (b[at + 1].toInt() and BYTE)
    private fun be32(b: ByteArray, at: Int) = (be16(b, at) shl BITS_16) or be16(b, at + 2)
    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and BYTE) or (b[at + 1].toInt() and BYTE shl BITS_8)
    private fun le32(b: ByteArray, at: Int) = le16(b, at) or (le16(b, at + 2) shl BITS_16)

    private const val MAX_NAME = 32
    private const val MAX_SOURCE_RATE = 192_000L
    private const val TAG = 4
    private const val CHUNK_HEAD = 8
    private const val FORM_TYPE = 8
    private const val AIFF_HEADER = 12
    private const val WAV_HEADER = 12
    private const val SSND_HEAD = 8
    private const val COMM_SIZE = 18
    private const val COMM_BITS = 6
    private const val COMM_RATE = 8
    private const val MANTISSA = 8
    private const val EXP_MASK = 0x7FFF
    private const val EXP_BIAS = 16383
    private const val MANT_BITS = 63
    private const val BITS_8 = 8
    private const val BITS_16 = 16
    private const val SIGN = 128
    private const val BYTE = 0xFF
    private const val PCM = 1
    private const val FMT_SIZE = 16
    private const val FMT_AT = 20
    private const val FMT_RATE = 4
    private const val FMT_BYTE_RATE = 8
    private const val FMT_ALIGN = 12
    private const val FMT_BITS = 14
    private const val DATA_AT = 36
    private const val WAV_HEADER_OUT = 44
}
