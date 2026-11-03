/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.graphics.Bitmap
import android.graphics.Typeface
import org.fcitx.fcitx5.android.input.theme.Crop
import org.fcitx.fcitx5.android.input.theme.Insets
import org.fcitx.fcitx5.android.input.theme.NineSlice
import org.fcitx.fcitx5.android.input.theme.Picture
import org.fcitx.fcitx5.android.input.theme.ThemeLook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThemeAssetsTest {

    // files of the sizes given, decoded at the sample asked; each decode counted
    private class FakeDecoder(val sizes: Map<String, IntArray>) : ThemeAssets.Decoder {
        val decoded = mutableListOf<Pair<String, Int>>()
        var fonts = 0

        override fun size(path: String) = sizes[path]

        override fun decode(path: String, sample: Int): Bitmap? {
            val s = sizes[path] ?: return null
            decoded += path to sample
            return Bitmap.createBitmap(s[0] / sample, s[1] / sample, Bitmap.Config.ARGB_8888)
        }

        override fun typeface(path: String): Typeface? {
            fonts++
            return Typeface.DEFAULT
        }
    }

    private val queued = mutableListOf<Runnable>()

    private lateinit var decoder: FakeDecoder

    private fun use(vararg files: Pair<String, IntArray>) {
        decoder = FakeDecoder(files.toMap())
        ThemeAssets.decoder = decoder
    }

    @Before
    fun setUp() {
        ThemeAssets.clear()
        // the decoding thread a queue run by hand; the main thread at once
        ThemeAssets.runner = { queued += it }
        ThemeAssets.poster = { it.run() }
    }

    @After
    fun tearDown() {
        ThemeAssets.clear()
    }

    private fun runQueued() {
        while (queued.isNotEmpty()) queued.removeAt(0).run()
    }

    private val look = ThemePreset.PixelLight.look

    private fun slice(file: String) = NineSlice(file, Insets(0, 0, 0, 0), 3)

    @Test
    fun aBoardsImagesAreDecodedOnceEachOffTheMainThread() {
        use("a.png" to intArrayOf(30, 30), "b.png" to intArrayOf(30, 30))
        val images = ThemeAssets.boardImages(look, "qwerty", listOf(slice("a.png"), slice("b.png"), slice("a.png")))
        assertFalse("not till its thread ran", images.ready)
        var told = 0
        images.whenReady { told++ }
        runQueued()
        assertTrue(images.ready)
        assertEquals(1, told)
        assertEquals(listOf("a.png" to 1, "b.png" to 1), decoder.decoded)
        assertNotNull(images.bitmap("a.png"))
        assertEquals(30, images.size("b.png")!![0])
        // the same board again: what was decoded, nothing decoded again
        assertSame(images, ThemeAssets.boardImages(look, "qwerty", listOf(slice("b.png"), slice("a.png"))))
        runQueued()
        assertEquals(2, decoder.decoded.size)
        // told at once once in
        images.whenReady { told++ }
        assertEquals(2, told)
    }

    @Test
    fun aBoardOfNoImagesIsInAtOnceAndAFileThatDoesNotReadIsNone() {
        use("a.png" to intArrayOf(30, 30))
        assertSame(BoardImages.NONE, ThemeAssets.boardImages(look, "nine", emptyList()))
        val images = ThemeAssets.boardImages(look, "nine", listOf(slice("gone.png"), slice("a.png")))
        runQueued()
        assertNull(images.bitmap("gone.png"))
        assertNotNull(images.bitmap("a.png"))
    }

    @Test
    fun theKeyboardDrawnLongestAgoGoesOverTheLimit() {
        // 1024 × 1024 × 4 = 4 MB each, a board of three 12 MB: two boards in the 24 MB, the third drops the first
        val side = 1024
        val files = (1..9).map { "$it.png" to intArrayOf(side, side) }.toTypedArray()
        use(*files)
        fun board(name: String, from: Int) =
            ThemeAssets.boardImages(look, name, (from until from + 3).map { slice("$it.png") }).also { runQueued() }
        val first = board("qwerty", 1)
        board("nine", 4)
        assertEquals(24L * 1024 * 1024, ThemeAssets.boardBytes())
        board("number", 7)
        assertTrue(ThemeAssets.boardBytes() <= ThemeAssets.BOARDS_LIMIT)
        // the first made again: decoded anew
        val again = ThemeAssets.boardImages(look, "qwerty", (1..3).map { slice("$it.png") })
        assertFalse(again === first)
    }

    @Test
    fun anotherThemesFilesDropWhatWasDecodedButNotAnotherColour() {
        use("a.png" to intArrayOf(30, 30), "p.png" to intArrayOf(30, 30), "q.png" to intArrayOf(30, 30))
        fun board(l: ThemeLook) = ThemeAssets.boardImages(l, "qwerty", listOf(slice("a.png"))).also { runQueued() }
        val first = board(pictured())
        // the same files, a brightness and a colour changed: kept
        val recoloured = pictured().let { it.copy(picture = it.picture!!.copy(brightness = 40), palette = ThemePreset.PixelDark.look.palette) }
        assertSame(first, board(recoloured))
        // another picture: let go
        val other = pictured().let { it.copy(picture = it.picture!!.copy(portrait = Crop("q.png", null, 0))) }
        board(other)
        assertEquals(2, decoder.decoded.size)
        assertEquals("the first theme's let go", 30L * 30 * 4, ThemeAssets.boardBytes())
    }

    private fun pictured(blur: Int = 0) = look.copy(picture = Picture("src.png", Crop("p.png", null, 0), Crop("l.png", null, 0), 100, blur))

    @Test
    fun thePictureIsDecodedNoWiderThanItIsDrawnOnceAnOrientation() {
        use("p.png" to intArrayOf(4096, 4096), "l.png" to intArrayOf(800, 400))
        val l: ThemeLook = pictured()
        val p = ThemeAssets.picture(l, false, 0, 1000, 2f)!!
        // halved while it stays 1000 wide: 4096 → 2048 → 1024
        assertEquals(1024, p.width)
        assertSame(p, ThemeAssets.picture(l, false, 0, 1000, 2f))
        assertEquals(listOf("p.png" to 4), decoder.decoded)
        // its blurs from it, each once, small
        val blurred = ThemeAssets.picture(l, false, 8, 1000, 2f)!!
        assertEquals(256, blurred.width)
        assertSame(blurred, ThemeAssets.picture(l, false, 8, 1000, 2f))
        assertEquals(1, decoder.decoded.size)
        // turned: the landscape cut, the portrait's let go
        assertEquals(800, ThemeAssets.picture(l, true, 0, 2000, 2f)!!.width)
        ThemeAssets.picture(l, false, 0, 1000, 2f)
        assertEquals(listOf("p.png" to 4, "l.png" to 1, "p.png" to 4), decoder.decoded)
    }

    @Test
    fun noPictureOrOneThatDoesNotReadIsNone() {
        use()
        assertNull(ThemeAssets.picture(look, false, 0, 1000, 2f))
        assertNull(ThemeAssets.picture(pictured(), false, 0, 1000, 2f))
    }

    @Test
    fun theSystemFontAsItIsIsNoneAndAFontIsReadOnce() {
        use()
        assertNull(ThemeAssets.typeface(look, null, 400))
        assertNotNull(ThemeAssets.typeface(look, null, 700))
        assertNotNull(ThemeAssets.typeface(look, "f.ttf", 400))
        ThemeAssets.typeface(look, "f.ttf", 400)
        assertEquals(1, decoder.fonts)
    }
}
