/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.graphics.Bitmap
import android.graphics.Typeface
import org.fcitx.fcitx5.android.input.theme.BarBase
import org.fcitx.fcitx5.android.input.theme.BoardBase
import org.fcitx.fcitx5.android.input.theme.Crop
import org.fcitx.fcitx5.android.input.theme.Insets
import org.fcitx.fcitx5.android.input.theme.KeyFill
import org.fcitx.fcitx5.android.input.theme.KeyPressed
import org.fcitx.fcitx5.android.input.theme.KindStyle
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

    private val realDecoder = ThemeAssets.decoder
    private val realRunner = ThemeAssets.runner
    private val realPoster = ThemeAssets.poster

    @Before
    fun setUp() {
        ThemeAssets.clear()
        // the decoding thread a queue run by hand; the main thread at once
        ThemeAssets.runner = { queued += it }
        ThemeAssets.poster = { it.run() }
    }

    @After
    fun tearDown() {
        ThemeAssets.decoder = realDecoder
        ThemeAssets.runner = realRunner
        ThemeAssets.poster = realPoster
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
    fun aTallPictureIsDecodedNoBiggerThanTheLimit() {
        // 1080 × 4096 drawn 1080 wide: as wide as it is drawn, but 17 MB and over the highest whole
        assertEquals(2, ThemeAssets.pictureSample(1080, 4096, 1080))
        assertEquals(1, ThemeAssets.pictureSample(1080, 2400, 1080))
        // a square 4096 drawn 4096 wide (a tablet turned): the pixels' limit
        assertEquals(2, ThemeAssets.pictureSample(4096, 4096, 4096))
        // wider than drawn: halved while as wide
        assertEquals(4, ThemeAssets.pictureSample(4096, 4096, 1000))
    }

    @Test
    fun aPlainThemePickedLetsThePicturedOnesFilesGo() {
        use("p.png" to intArrayOf(1000, 800), "a.png" to intArrayOf(30, 30))
        val pictured = pictured(blur = 8).copy(base = BoardBase.Picture)
        ThemeAssets.prefetch(pictured, false, 1000, 2f)
        runQueued()
        // ahead: the sharp one and the blur it is shown at
        assertEquals(2, ThemeAssets.pictureCount())
        ThemeAssets.boardImages(pictured, "qwerty", listOf(slice("a.png")))
        runQueued()
        assertTrue(ThemeAssets.boardBytes() > 0)
        // a theme of no files, picked: nothing of the other's kept, though it has nothing to decode
        ThemeAssets.prefetch(look, false, 1000, 2f)
        assertEquals(0L, ThemeAssets.boardBytes())
        assertEquals(0, ThemeAssets.pictureCount())
        assertEquals(emptyList<Runnable>(), queued)
    }

    @Test
    fun aPictureIsDecodedAheadAtEachBlurTheThemeShowsIt() {
        use("p.png" to intArrayOf(1000, 800))
        val l = pictured(blur = 8).let {
            it.copy(
                base = BoardBase.Picture,
                bar = BarBase.Picture(4, 80),
                keys = it.keys.copy(letter = KindStyle(KeyFill.Picture(12, 100), KeyPressed.Darken)),
            )
        }
        ThemeAssets.prefetch(l, false, 1000, 2f)
        assertEquals("not on the asking thread", 0, decoder.decoded.size)
        runQueued()
        // the sharp one once, the three blurs from it
        assertEquals(listOf("p.png" to 1), decoder.decoded)
        assertEquals(4, ThemeAssets.pictureCount())
        ThemeAssets.picture(l, false, 12, 1000, 2f)
        assertEquals(1, decoder.decoded.size)
    }

    @Test
    fun aPictureThatRunsOutOfMemoryIsNoneAndTheBoardOfOneDecodesNothing() {
        use("p.png" to intArrayOf(1000, 800), "a.png" to intArrayOf(30, 30))
        ThemeAssets.decoder = object : ThemeAssets.Decoder by decoder {
            override fun decode(path: String, sample: Int): Bitmap? = throw OutOfMemoryError("test")
        }
        assertNull(ThemeAssets.picture(pictured(), false, 0, 1000, 2f))
        val images = ThemeAssets.boardImages(look, "qwerty", listOf(slice("a.png")))
        runQueued()
        assertTrue("told all the same", images.ready)
        assertNull(images.bitmap("a.png"))
    }

    @Test
    fun aThumbnailIsDecodedSmallOffTheMainThreadOnce() {
        use("p.png" to intArrayOf(4096, 4096))
        val got = mutableListOf<Bitmap?>()
        ThemeAssets.thumbnail(pictured(), 300, 1000, 2f) { got += it }
        ThemeAssets.thumbnail(pictured(), 300, 1000, 2f) { got += it }
        assertEquals("not on the main thread", 0, decoder.decoded.size)
        runQueued()
        // 4096 → 512 as decoded (halved while 300 wide), scaled to 300
        assertEquals(listOf("p.png" to 8), decoder.decoded)
        assertEquals(2, got.size)
        assertSame(got[0], got[1])
        assertEquals(300, got[0]!!.width)
        // again: at once, nothing decoded
        ThemeAssets.thumbnail(pictured(), 300, 1000, 2f) { got += it }
        assertSame(got[0], got[2])
        assertEquals(1, decoder.decoded.size)
        assertTrue(ThemeAssets.thumbnailBytes() > 0)
        // blurred as under the keyboard: smaller still, its own
        ThemeAssets.thumbnail(pictured(blur = 8), 300, 1000, 2f) { got += it }
        runQueued()
        assertEquals(75, got[3]!!.width)
        // none for a theme of no picture, at once
        ThemeAssets.thumbnail(look, 300, 1000, 2f) { got += it }
        assertNull(got[4])
        ThemeAssets.clear()
        assertEquals(0L, ThemeAssets.thumbnailBytes())
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
