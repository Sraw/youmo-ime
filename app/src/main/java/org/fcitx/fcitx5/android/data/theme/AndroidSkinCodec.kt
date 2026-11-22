/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.data.layout.CustomLayouts
import org.fcitx.fcitx5.android.input.theme.SkinZip
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.fcitx.fcitx5.android.scene.skin.Pixels
import org.fcitx.fcitx5.android.scene.skin.SkinImage
import org.fcitx.fcitx5.android.scene.skin.SkinLimits
import org.fcitx.fcitx5.android.scene.skin.SkinPlatform
import org.fcitx.fcitx5.android.utils.LowMemory
import org.fcitx.fcitx5.android.utils.appContext
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** An image past [SkinLimits.MAX_IMAGE_PIXELS] (by its header) is not decoded; decoded unpremultiplied, read a tile at a time. */
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

            // held to the bitmap: getPixels throws past it
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

    override fun layoutSources(): LayoutCheck.Sources = CustomLayouts.sources

    /**
     * Read as a stream into one array of the length it gives (no doubling buffer); a byte past it or short of it is no
     * payload. A phone low on memory takes half as much.
     */
    override fun sogouPlain(container: ByteArray, maxBytes: Int): ByteArray? = sogouPlain(container, maxBytes, LowMemory.on(appContext))

    @VisibleForTesting
    @Suppress("SwallowedException", "ReturnCount")
    internal fun sogouPlain(container: ByteArray, maxBytes: Int, lowMemory: Boolean): ByteArray? {
        val sealed = container.size - SOGOU_HEADER
        if (sealed <= 0) return null
        val limit = if (lowMemory) minOf(maxBytes, LOW_MEMORY_MAX_BYTES) else maxBytes
        val inflater = Inflater()
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(hex(SkinPlatform.SOGOU_KEY), "AES"), IvParameterSpec(hex(SkinPlatform.SOGOU_IV)))
            BufferedInputStream(CipherInputStream(ByteArrayInputStream(container, SOGOU_HEADER, sealed), cipher), STREAM_BUFFER).use { plain ->
                val head = ByteArray(Int.SIZE_BYTES)
                if (!plain.readFully(head)) return null
                val raw = head.foldIndexed(0L) { i, v, b -> v or ((b.toLong() and BYTE) shl (Byte.SIZE_BITS * i)) }
                if (raw > limit || (raw > SkinLimits.RATIO_FROM && raw > SkinLimits.MAX_RATIO.toLong() * sealed)) return null
                val out = ByteArray(raw.toInt())
                val inflated = InflaterInputStream(plain, inflater, STREAM_BUFFER)
                if (!inflated.readFully(out) || inflated.read() != -1) return null
                // to its end, so a wrong padding is found
                while (plain.read(head) != -1) Unit
                out
            }
        } catch (e: IOException) {
            null
        } catch (e: GeneralSecurityException) {
            null
        } finally {
            // its native memory now, not at a later GC
            inflater.end()
        }
    }

    private fun InputStream.readFully(into: ByteArray): Boolean {
        var at = 0
        while (at < into.size) {
            val n = read(into, at, into.size - at)
            if (n < 0) return false
            at += n
        }
        return true
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(HEX).toByte() }

    private const val PNG_QUALITY = 100

    // "Skin" and a version, before the cipher text
    private const val SOGOU_HEADER = 8
    const val LOW_MEMORY_MAX_BYTES = 64 * 1024 * 1024
    private const val STREAM_BUFFER = 64 * 1024
    private const val BYTE = 0xFFL
    private const val HEX = 16
}
