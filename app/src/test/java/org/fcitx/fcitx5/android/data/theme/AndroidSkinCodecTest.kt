/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.scene.skin.Pixels
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

/** A skin's images through Android's codec: written and read back as they were, one too large not decoded. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidSkinCodecTest {
    @Test
    fun pixelsRoundTrip() {
        // opaque colours and a transparent corner: not premultiplied either way
        val p = Pixels(5, 4, IntArray(20) { if (it == 0) 0 else 0xFF000000.toInt() or (it * 0x0A0B0C) })
        val png = AndroidSkinCodec.png(p)
        assertEquals("PNG", String(png, 1, 3))
        val img = AndroidSkinCodec.image(png)!!
        assertEquals(5 to 4, img.width to img.height)
        assertEquals(0, img.pixels(0, 0, 1, 1)[0, 0] ushr 24)
        assertArrayEquals(p.crop(1, 1, 3, 2)!!.argb, img.pixels(1, 1, 3, 2).argb)
    }

    @Test
    fun aHugeImageIsNotDecoded() {
        // a header of 5000×5000 (25 M pixels): refused from its size alone
        assertNull(AndroidSkinCodec.image(header(5000, 5000)))
        assertNull(AndroidSkinCodec.image("not an image".toByteArray()))
    }

    @Test
    fun legacyTextIsGb18030() {
        assertEquals("名", AndroidSkinCodec.legacyText(byteArrayOf(0xC3.toByte(), 0xFB.toByte())))
    }

    // a PNG of its signature, an IHDR of [w]×[h] and IEND: what a header read sees
    private fun header(w: Int, h: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            out.write(ByteBuffer.allocate(4).putInt(data.size).array())
            val body = type.toByteArray() + data
            out.write(body)
            out.write(ByteBuffer.allocate(4).putInt(CRC32().also { it.update(body) }.value.toInt()).array())
        }
        chunk("IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(6).put(0).put(0).put(0).array())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}
