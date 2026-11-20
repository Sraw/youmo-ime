/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

/** An animated WebP's header read chunk head by chunk head, no frame decoded: a few-MB file costs a few hundred small reads. */
object AnimatedWebp {

    /** How many it read (fewer at the end). */
    fun interface Source {
        fun read(at: Long, into: ByteArray): Int
    }

    class Header(val width: Int, val height: Int, val frames: Int, val shortestFrameMs: Int)

    /** Null where not animated WebP or cut short. Counting stops past [maxFrames]; VP8X only first (libwebp's rule); at most [maxFrames] + [OTHER_CHUNKS] chunks walked, so padding with empty chunks is refused. */
    fun header(source: Source, length: Long, maxFrames: Int = Int.MAX_VALUE): Header? {
        val head = ByteArray(RIFF_HEAD)
        if (source.read(0, head) < RIFF_HEAD || !head.text(0, "RIFF") || !head.text(WEBP_AT, "WEBP")) return null
        // the RIFF's own size, the file's where it says more (a file cut short is read as far as it goes)
        val end = minOf(length, CHUNK_HEAD + head.le32(SIZE_AT))
        var at = RIFF_HEAD.toLong()
        var width = 0
        var height = 0
        var animated = false
        var frames = 0
        var shortest = Int.MAX_VALUE
        val chunk = ByteArray(CHUNK_HEAD + ANMF_HEAD)
        var chunks = 0L
        val maxChunks = maxFrames.toLong() + OTHER_CHUNKS
        while (at + CHUNK_HEAD <= end) {
            if (++chunks > maxChunks) return null
            val got = source.read(at, chunk)
            if (got < CHUNK_HEAD) return null
            val size = chunk.le32(FOURCC)
            when {
                chunk.text(0, "VP8X") -> {
                    if (at != RIFF_HEAD.toLong() || got < CHUNK_HEAD + VP8X_SIZE) return null
                    animated = chunk[CHUNK_HEAD].toInt() and ANIMATION_FLAG != 0
                    width = chunk.le24(CHUNK_HEAD + VP8X_WIDTH) + 1
                    height = chunk.le24(CHUNK_HEAD + VP8X_HEIGHT) + 1
                }
                chunk.text(0, "ANMF") -> {
                    // a frame shorter than its own head says nothing of itself (its duration would be the next chunk's bytes)
                    if (size < ANMF_HEAD || got < CHUNK_HEAD + ANMF_HEAD) return null
                    frames++
                    shortest = minOf(shortest, chunk.le24(CHUNK_HEAD + ANMF_DURATION))
                    if (frames > maxFrames) break
                }
            }
            // a chunk's data is padded to an even size
            at += CHUNK_HEAD + size + (size and 1)
        }
        if (!animated || frames == 0) return null
        return Header(width, height, frames, shortest)
    }

    fun header(file: java.io.File, maxFrames: Int = Int.MAX_VALUE): Header? = java.io.RandomAccessFile(file, "r").use { f ->
        header({ at, into ->
            f.seek(at)
            var n = 0
            while (n < into.size) {
                val r = f.read(into, n, into.size - n)
                if (r < 0) break
                n += r
            }
            n
        }, f.length(), maxFrames)
    }

    fun header(bytes: ByteArray, maxFrames: Int = Int.MAX_VALUE): Header? = header({ at, into ->
        if (at >= bytes.size) 0 else minOf(into.size, bytes.size - at.toInt()).also { bytes.copyInto(into, 0, at.toInt(), at.toInt() + it) }
    }, bytes.size.toLong(), maxFrames)

    private fun ByteArray.text(at: Int, s: String) = s.indices.all { this[at + it] == s[it].code.toByte() }
    private fun ByteArray.u8(at: Int) = this[at].toInt() and BYTE
    private fun ByteArray.le24(at: Int) = u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16)
    private fun ByteArray.le32(at: Int) = (le24(at).toLong() or (u8(at + 3).toLong() shl 24))

    /** VP8X, ICCP, ANIM, EXIF, XMP and a few unknown ones. */
    const val OTHER_CHUNKS = 64

    private const val BYTE = 0xff
    private const val RIFF_HEAD = 12
    private const val SIZE_AT = 4
    private const val WEBP_AT = 8
    private const val CHUNK_HEAD = 8
    private const val FOURCC = 4
    private const val VP8X_SIZE = 10
    private const val VP8X_WIDTH = 4
    private const val VP8X_HEIGHT = 7
    private const val ANIMATION_FLAG = 0x02
    private const val ANMF_HEAD = 16
    private const val ANMF_DURATION = 12
}
