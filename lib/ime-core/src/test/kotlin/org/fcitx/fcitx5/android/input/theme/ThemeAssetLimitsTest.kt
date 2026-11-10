/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.fcitx.fcitx5.android.input.theme.LookFixtures.PALETTE
import org.fcitx.fcitx5.android.scene.look.KeyLabel
import org.fcitx.fcitx5.android.scene.look.KeyOverride
import org.fcitx.fcitx5.android.scene.look.Keys
import org.fcitx.fcitx5.android.scene.look.LookDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class ThemeAssetLimitsTest {

    @Test
    fun limitsTellWhatIsPast() {
        val l = ThemeAssetLimits
        assertNull(l.entry(300, l.MAX_FILE_BYTES, 0))
        assertEquals(ThemeAssetLimits.Breach.Entries, l.entry(301, 1, 0))
        assertEquals(ThemeAssetLimits.Breach.FileSize, l.entry(1, l.MAX_FILE_BYTES + 1, 0))
        assertEquals(ThemeAssetLimits.Breach.TotalSize, l.entry(3, 1, l.MAX_TOTAL_BYTES))
        assertNull(l.picture(4096, 4096))
        assertEquals(ThemeAssetLimits.Breach.PictureSide, l.picture(4097, 10))
        assertNull(l.keyImage(1024, 1024, l.MAX_KEY_IMAGE_BYTES))
        assertEquals(ThemeAssetLimits.Breach.KeyImageSide, l.keyImage(10, 1025, 1))
        assertEquals(ThemeAssetLimits.Breach.KeyImageSize, l.keyImage(10, 10, l.MAX_KEY_IMAGE_BYTES + 1))
        val look = LookDefaults.look(PALETTE)
        assertNull(l.look(look))
        val many = (1..401).associate { "qwerty/k$it" to KeyOverride(null, null, KeyLabel.Show) }
        assertEquals(ThemeAssetLimits.Breach.Overrides, l.look(look.copy(keys = look.keys.copy(overrides = many))))
        // the engine's limit, which its 3.0 reader holds a file to too
        assertEquals(Keys.MAX_OVERRIDES, l.MAX_OVERRIDES)
    }

    @Test
    fun aPacksOwnFilesHaveTheirLimits() {
        val l = ThemeAssetLimits
        assertNull(l.themeXml(l.MAX_THEME_XML_BYTES))
        assertEquals(ThemeAssetLimits.Breach.ThemeXml, l.themeXml(l.MAX_THEME_XML_BYTES + 1))
        assertNull(l.layouts(List(16) { l.MAX_LAYOUT_BYTES }))
        assertEquals(ThemeAssetLimits.Breach.Layouts, l.layouts(List(17) { 1L }))
        assertEquals(ThemeAssetLimits.Breach.LayoutSize, l.layouts(listOf(1, l.MAX_LAYOUT_BYTES + 1)))
        assertNull(l.preview(2048, 2048, l.MAX_PREVIEW_BYTES))
        assertEquals(ThemeAssetLimits.Breach.PreviewSize, l.preview(10, 10, l.MAX_PREVIEW_BYTES + 1))
        assertEquals(ThemeAssetLimits.Breach.PreviewSide, l.preview(2049, 10, 1))
        assertNull(l.icon(l.MAX_ICON_BYTES))
        assertEquals(ThemeAssetLimits.Breach.IconSize, l.icon(l.MAX_ICON_BYTES + 1))
    }

    // an animated WebP's head as a file has it: RIFF, VP8X (animation flag), ANIM, its frames (each a few bytes of data)
    private fun webp(w: Int, h: Int, durations: List<Int>, animated: Boolean = true, oddData: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        fun le(o: ByteArrayOutputStream, v: Int, n: Int) = repeat(n) { o.write((v shr (8 * it)) and 0xff) }
        fun chunk(tag: String, data: ByteArray) {
            out.write(tag.toByteArray())
            le(out, data.size, 4)
            out.write(data)
            if (data.size % 2 == 1) out.write(0)
        }
        fun bytes(f: (ByteArrayOutputStream) -> Unit) = ByteArrayOutputStream().also(f).toByteArray()
        out.write("RIFF".toByteArray())
        le(out, 0, 4)
        out.write("WEBP".toByteArray())
        chunk("VP8X", bytes { o -> o.write(if (animated) 0x02 else 0); le(o, 0, 3); le(o, w - 1, 3); le(o, h - 1, 3) })
        chunk("ANIM", ByteArray(6))
        durations.forEach { d ->
            chunk("ANMF", bytes { o -> le(o, 0, 6); le(o, w - 1, 3); le(o, h - 1, 3); le(o, d, 3); o.write(0); o.write(ByteArray(if (oddData) 5 else 4)) })
        }
        return out.toByteArray().also { b ->
            val size = b.size - 8
            repeat(4) { b[4 + it] = (size shr (8 * it)).toByte() }
        }
    }

    @Test
    fun anAnimatedWebpsHeadIsReadWithoutItsFrames() {
        val h = AnimatedWebp.header(webp(1080, 720, listOf(40, 33, 50), oddData = true))!!
        assertEquals(1080, h.width)
        assertEquals(720, h.height)
        assertEquals(3, h.frames)
        assertEquals(33, h.shortestFrameMs)
        // a still WebP, no frame, not a WebP, cut short: none
        assertNull(AnimatedWebp.header(webp(10, 10, listOf(40), animated = false)))
        assertNull(AnimatedWebp.header(webp(10, 10, emptyList())))
        assertNull(AnimatedWebp.header("GIF89a......".toByteArray()))
        assertNull(AnimatedWebp.header(webp(10, 10, listOf(40)).copyOf(20)))
        // counting stops past the limit asked
        assertEquals(3, AnimatedWebp.header(webp(10, 10, List(50) { 40 }), maxFrames = 2)!!.frames)
    }

    @Test
    fun anAnimatedPicturePastItsLimitsIsDrawnStill() {
        val l = ThemeAssetLimits
        fun of(w: Int, h: Int, frames: Int, ms: Int) = AnimatedWebp.header(webp(w, h, List(frames) { ms }))
        assertNull(l.animated(l.MAX_ANIMATED_BYTES, of(1440, 1080, 300, 33)))
        assertEquals(ThemeAssetLimits.Still.Size, l.animated(l.MAX_ANIMATED_BYTES + 1, of(10, 10, 1, 40)))
        assertEquals(ThemeAssetLimits.Still.Unread, l.animated(10, null))
        assertEquals(ThemeAssetLimits.Still.Side, l.animated(10, of(10, 1441, 1, 40)))
        assertEquals(ThemeAssetLimits.Still.Frames, l.animated(10, of(10, 10, 301, 40)))
        assertEquals(ThemeAssetLimits.Still.FrameRate, l.animated(10, of(10, 10, 2, 32)))
        assertEquals(ThemeAssetLimits.Still.FrameRate, l.animated(10, of(10, 10, 2, 0)))
    }
}
