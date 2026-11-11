/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.fcitx.fcitx5.android.input.theme.SkinZip
import org.fcitx.fcitx5.android.scene.skin.Pixels
import org.fcitx.fcitx5.android.scene.skin.SkinImage
import org.fcitx.fcitx5.android.scene.skin.SkinLimits
import java.io.ByteArrayOutputStream

/**
 * A skin's images decoded and a converted one's written by Android ([ThemeFilesManager.openSkin]): an image's size
 * read from its header first, one past [SkinLimits.MAX_IMAGE_PIXELS] not decoded; decoded not premultiplied, as
 * [Pixels] is, its pixels read a part at a time ([SkinImage.pixels]: a key's tile of an atlas, not the atlas whole).
 */
internal object AndroidSkinCodec : ThemeFilesManager.SkinCodec {
    override fun image(bytes: ByteArray): SkinImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w.toLong() * h > SkinLimits.MAX_IMAGE_PIXELS) return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
        }
        val bitmap = decode(bytes, options) ?: return null
        return object : SkinImage {
            override val width = bitmap.width
            override val height = bitmap.height

            // held to the bitmap, as PixelsImage's crop is: getPixels throws past it
            override fun pixels(x: Int, y: Int, w: Int, h: Int): Pixels {
                val l = x.coerceIn(0, width)
                val t = y.coerceIn(0, height)
                val r = (x.toLong() + w).coerceIn(l.toLong(), width.toLong()).toInt()
                val b = (y.toLong() + h).coerceIn(t.toLong(), height.toLong()).toInt()
                if (r <= l || b <= t) return Pixels(1, 1)
                val out = Pixels(r - l, b - t)
                bitmap.getPixels(out.argb, 0, out.width, l, t, out.width, out.height)
                return out
            }
        }
    }

    // null where it would not fit in memory: an image not read, as one past the limit is
    @Suppress("SwallowedException")
    private fun decode(bytes: ByteArray, options: BitmapFactory.Options): Bitmap? = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (e: OutOfMemoryError) {
        null
    }

    override fun legacyText(bytes: ByteArray): String = String(bytes, SkinZip.GB18030)

    override fun png(pixels: Pixels): ByteArray {
        // colours not premultiplied, as Bitmap's int arrays are
        val bitmap = Bitmap.createBitmap(pixels.argb, pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private const val PNG_QUALITY = 100
}
