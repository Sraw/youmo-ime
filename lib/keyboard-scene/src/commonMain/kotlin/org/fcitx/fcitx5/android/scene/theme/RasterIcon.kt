/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

/**
 * A theme's PNG or WebP icon or sticker, its size read from its header (PNG's IHDR, WebP's VP8, VP8L or VP8X) without
 * decoding a pixel: what the app and the theme maker check before they decode one.
 */
object RasterIcon {
    const val MAX_SIDE = 256
    const val MAX_BYTES = 128 * 1024
    const val MAX_STICKER_SIDE = 512
    const val MAX_STICKER_BYTES = 256 * 1024
    const val MAX_STICKERS = 120

    /** A file name of a raster image, by its suffix; anything else an icon names is SVG. */
    fun named(file: String): Boolean = TYPES.any { file.endsWith(it, ignoreCase = true) }

    private val TYPES = listOf(".png", ".webp")

    class Header(val width: Int, val height: Int, val animated: Boolean)

    /** null: neither a PNG nor a WebP, cut short, or a side not positive (a PNG's over 2^31 reads negative). */
    fun header(b: ByteArray): Header? = (png(b) ?: webp(b))?.takeIf { it.width > 0 && it.height > 0 }

    /** What is wrong with [b] as an icon, or null. */
    fun iconProblem(b: ByteArray): String? = problem(b, MAX_SIDE, MAX_BYTES)

    fun stickerProblem(b: ByteArray): String? = problem(b, MAX_STICKER_SIDE, MAX_STICKER_BYTES)

    private fun problem(b: ByteArray, side: Int, bytes: Int): String? {
        val h = header(b) ?: return "not a PNG or WebP image"
        return when {
            h.animated -> "an animated WebP: only a still image is drawn"
            maxOf(h.width, h.height) > side -> "${h.width}×${h.height} px, over $side px a side"
            b.size > bytes -> "${b.size / KB} KB, over ${bytes / KB} KB"
            else -> null
        }
    }

    private const val KB = 1024

    private val PNG = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun png(b: ByteArray): Header? {
        if (b.size < PNG_HEADER || PNG.indices.any { u(b, it) != PNG[it] } || ascii(b, PNG_IHDR) != "IHDR") return null
        return Header(be(b, PNG_IHDR + 4), be(b, PNG_IHDR + 8), animated = false)
    }

    private fun webp(b: ByteArray): Header? {
        if (b.size < WEBP_CHUNK + CHUNK_HEAD || ascii(b, 0) != "RIFF" || ascii(b, WEBP_TAG) != "WEBP") return null
        val data = WEBP_CHUNK + CHUNK_HEAD
        return when (ascii(b, WEBP_CHUNK)) {
            "VP8 " -> if (b.size < data + VP8_HEAD) null else Header(le16(b, data + VP8_SIZE) and MASK14, le16(b, data + VP8_SIZE + 2) and MASK14, false)
            "VP8L" -> if (b.size < data + VP8L_HEAD || u(b, data) != VP8L_SIGNATURE) null else {
                val bits = u(b, data + 1) or (u(b, data + 2) shl BYTE) or (u(b, data + 3) shl 2 * BYTE) or (u(b, data + 4) shl 3 * BYTE)
                Header((bits and MASK14) + 1, ((bits ushr VP8L_HEIGHT_SHIFT) and MASK14) + 1, false)
            }
            "VP8X" -> if (b.size < data + VP8X_HEAD) null else {
                Header(le24(b, data + VP8X_WIDTH) + 1, le24(b, data + VP8X_WIDTH + 3) + 1, (u(b, data) and VP8X_ANIMATION) != 0)
            }
            else -> null
        }
    }

    private fun u(b: ByteArray, at: Int) = b[at].toInt() and 0xFF
    private fun ascii(b: ByteArray, at: Int) = (at until at + 4).map { u(b, it).toChar() }.joinToString("")
    private fun be(b: ByteArray, at: Int) = (u(b, at) shl 3 * BYTE) or (u(b, at + 1) shl 2 * BYTE) or (u(b, at + 2) shl BYTE) or u(b, at + 3)
    private fun le16(b: ByteArray, at: Int) = u(b, at) or (u(b, at + 1) shl BYTE)
    private fun le24(b: ByteArray, at: Int) = le16(b, at) or (u(b, at + 2) shl 2 * BYTE)

    private const val BYTE = 8
    private const val PNG_IHDR = 12
    private const val PNG_HEADER = 24
    private const val WEBP_TAG = 8
    private const val WEBP_CHUNK = 12
    private const val CHUNK_HEAD = 8
    private const val VP8_SIZE = 6
    private const val VP8_HEAD = 10
    private const val VP8L_HEAD = 5
    private const val VP8L_SIGNATURE = 0x2F
    private const val VP8L_HEIGHT_SHIFT = 14
    private const val VP8X_HEAD = 10
    private const val VP8X_WIDTH = 4
    private const val VP8X_ANIMATION = 0x02
    private const val MASK14 = 0x3FFF
}
