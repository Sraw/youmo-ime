/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.View
import org.fcitx.fcitx5.android.input.PictureHost
import org.fcitx.fcitx5.android.input.PicturePaint
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneFonts
import org.fcitx.fcitx5.android.scene.look.BarBase
import org.fcitx.fcitx5.android.scene.look.BoardBase
import org.fcitx.fcitx5.android.scene.look.Crop
import org.fcitx.fcitx5.android.scene.look.Insets
import org.fcitx.fcitx5.android.scene.look.KeyFill
import org.fcitx.fcitx5.android.scene.look.KeyPressed
import org.fcitx.fcitx5.android.scene.look.KindStyle
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.scene.look.NineSlice
import org.fcitx.fcitx5.android.scene.look.Picture
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    companion object {
        // the real ones, taken once before any test sets its own
        private lateinit var realDecoder: ThemeAssets.Decoder
        private lateinit var realRunner: (Runnable) -> Unit
        private lateinit var realPoster: (Runnable) -> Unit

        @BeforeClass
        @JvmStatic
        fun keepTheReal() {
            realDecoder = ThemeAssets.decoder
            realRunner = ThemeAssets.runner
            realPoster = ThemeAssets.poster
        }
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

    // a broken theme's keys drawn in the built-in theme's fonts (060-render-p2 U8, SceneFonts), its
    // candidates and picture its own: the built-in's system font asked between lets go of none of the
    // drawn theme's files, its picture decoding comes in, its font read once
    @Test
    fun theBuiltInsSystemFontLetsGoOfNoneOfTheDrawnThemesFiles() {
        use("p.png" to intArrayOf(1000, 800))
        val own = pictured().let { it.copy(text = it.text.copy(keyFont = "f.ttf")) }
        val builtIn = SceneFonts(ThemePreset.PixelDark.look)
        assertNotNull(ThemeAssets.typeface(own, "f.ttf", 400))
        var told = 0
        assertNull(ThemeAssets.pictureOrLater(own, false, 0, 1000, 2f) { told++ })
        // a frame of the keys while the picture decodes: their letters, their bold symbols
        builtIn.typeface(LookTheme.KEY_FONT, 400)
        builtIn.typeface(LookTheme.KEY_FONT, 700)
        builtIn.typeface("system", 700)
        runQueued()
        assertEquals(1, told)
        builtIn.typeface(LookTheme.KEY_FONT, 700)
        assertNotNull(ThemeAssets.pictureOrLater(own, false, 0, 1000, 2f) { told++ }!!.bitmap)
        assertNotNull(ThemeAssets.typeface(own, "f.ttf", 400))
        assertEquals(listOf("p.png"), decoder.decoded.map { it.first })
        assertEquals(1, decoder.fonts)
    }

    // [decoder]'s decodes held at the gate till it opens; [entered] when the first is held
    private class Gate(val decoder: ThemeAssets.Decoder) : ThemeAssets.Decoder by decoder {
        val entered = CountDownLatch(1)
        val open = CountDownLatch(1)
        val decodes = AtomicInteger()

        override fun decode(path: String, sample: Int): Bitmap? {
            decodes.incrementAndGet()
            entered.countDown()
            check(open.await(10, TimeUnit.SECONDS))
            return decoder.decode(path, sample)
        }
    }

    // [f] on a thread of its own, its result in [out]
    private fun thread(out: Array<Bitmap?>, f: () -> Bitmap?) = Thread { out[0] = f() }.apply { start() }

    // till [t] waits on a lock (another thread decoding what it asked for)
    private fun waitsOnALock(t: Thread) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (t.state != Thread.State.WAITING && t.state != Thread.State.BLOCKED) {
            check(System.nanoTime() < until) { "never waited: ${t.state}" }
            Thread.yield()
        }
    }

    @Test
    fun aPrefetchRunLateLetsTheThemePickedSinceKeepItsOwn() {
        use("p.png" to intArrayOf(1000, 800), "q.png" to intArrayOf(1000, 800), "a.png" to intArrayOf(30, 30))
        val a = pictured(blur = 8).copy(base = BoardBase.Picture)
        val b = a.copy(picture = a.picture!!.copy(portrait = Crop("q.png", null, 0)))
        // A picked, its prefetch queued behind; B picked and drawn before it runs
        ThemeAssets.prefetch(a, false, 1000, 2f)
        ThemeAssets.retain(b)
        assertNotNull(ThemeAssets.picture(b, false, 0, 1000, 2f))
        val images = ThemeAssets.boardImages(b, "qwerty", listOf(slice("a.png")))
        runQueued()
        // A's run: nothing of A decoded, nothing of B's let go
        assertFalse(decoder.decoded.any { it.first == "p.png" })
        assertEquals(1, ThemeAssets.pictureCount())
        assertTrue(images.ready)
        assertSame(images, ThemeAssets.boardImages(b, "qwerty", listOf(slice("a.png"))))
        assertTrue(ThemeAssets.boardBytes() > 0)
        assertEquals(listOf("q.png" to 1, "a.png" to 1), decoder.decoded)
    }

    @Test
    fun twoAskingForAPictureLetGoAsTheyWaitDecodeItOnceAndKeepItNot() {
        use("p.png" to intArrayOf(1000, 800))
        val gate = Gate(decoder)
        ThemeAssets.decoder = gate
        val l = pictured()
        val first = arrayOfNulls<Bitmap>(1)
        val second = arrayOfNulls<Bitmap>(1)
        val t1 = thread(first) { ThemeAssets.picture(l, false, 0, 1000, 2f) }
        check(gate.entered.await(10, TimeUnit.SECONDS))
        val t2 = thread(second) { ThemeAssets.picture(l, false, 0, 1000, 2f) }
        waitsOnALock(t2)
        // the files written anew as they wait
        ThemeAssets.clear()
        gate.open.countDown()
        t1.join(10_000)
        t2.join(10_000)
        assertNotNull(first[0])
        assertSame(first[0], second[0])
        assertEquals(1, gate.decodes.get())
        assertEquals("not put back after the clear", 0, ThemeAssets.pictureCount())
        // asked again: decoded anew
        assertNotNull(ThemeAssets.picture(l, false, 0, 1000, 2f))
        assertEquals(2, gate.decodes.get())
    }

    @Test
    fun aViewDrawingWaitsNotForAPictureDecodedOnAnotherThreadAndIsToldWhenItIsIn() {
        use("p.png" to intArrayOf(1000, 800))
        val gate = Gate(decoder)
        ThemeAssets.decoder = gate
        val l = pictured()
        val ahead = arrayOfNulls<Bitmap>(1)
        val t = thread(ahead) { ThemeAssets.picture(l, false, 0, 1000, 2f) }
        check(gate.entered.await(10, TimeUnit.SECONDS))
        var told = 0
        // the sharp one, and a blur made from it: neither waited for
        assertNull(ThemeAssets.pictureOrLater(l, false, 0, 1000, 2f) { told++ })
        assertNull(ThemeAssets.pictureOrLater(l, false, 8, 1000, 2f) { told++ })
        gate.open.countDown()
        t.join(10_000)
        // the blur made on the decode thread, from the sharp one decoded
        runQueued()
        assertEquals("each told once it is in", 2, told)
        assertSame(ahead[0], ThemeAssets.pictureOrLater(l, false, 0, 1000, 2f) { told++ }!!.bitmap)
        // the blur itself made next on the decode thread, from the sharp one in
        assertNull(ThemeAssets.pictureOrLater(l, false, 8, 1000, 2f) { told++ })
        runQueued()
        assertEquals(3, told)
        assertNotNull(ThemeAssets.pictureOrLater(l, false, 8, 1000, 2f) { told++ }!!.bitmap)
        assertEquals(1, gate.decodes.get())
        assertEquals(3, told)
    }

    @Test
    fun aBlurIsMadeForTheWidthItIsDrawnAt() {
        use("p.png" to intArrayOf(1000, 800))
        val l = pictured()
        val wide = ThemeAssets.picture(l, false, 8, 1000, 2f)!!
        // nearly as wide: the same
        assertSame(wide, ThemeAssets.picture(l, false, 8, 980, 2f))
        // a floating keyboard's, half as wide: its own, blurred twice the picture's pixels
        val narrow = ThemeAssets.picture(l, false, 8, 500, 2f)!!
        assertTrue(wide !== narrow)
        assertEquals(2 * ThemeAssets.blurRadius(8, 1000, 1000, 2f), ThemeAssets.blurRadius(8, 500, 1000, 2f))
        // both from the one sharp picture
        assertEquals(listOf("p.png" to 1), decoder.decoded)
    }

    // two views drawing one blur at two widths at once (the theme page's preview and the keyboard, split
    // screen) once let each other's go: each asked anew, blurred again, neither ever getting one
    @Test
    fun twoWidthsDrawnInTurnAreEachBlurredOnceAndKept() {
        use("p.png" to intArrayOf(1000, 800))
        val l = pictured(blur = 8)
        fun ask(width: Int) = ThemeAssets.pictureOrLater(l, false, 8, width, 2f) {}?.bitmap
        // in turn till both are in: the sharp one, then each width's blur, on the decode thread
        repeat(3) {
            ask(1000)
            ask(500)
            runQueued()
        }
        val wide = ask(1000)!!
        val narrow = ask(500)!!
        assertTrue(wide !== narrow)
        // asked in turn again, as each view draws a frame: the same ones, nothing more to make
        repeat(4) {
            assertSame(wide, ask(1000))
            assertSame(narrow, ask(500))
        }
        assertTrue("nothing queued", queued.isEmpty())
        assertEquals(listOf("p.png" to 1), decoder.decoded)
    }

    // a blur kept for every width ever drawn (a floating keyboard resized again and again) would keep
    // a bitmap each: past the last few widths the oldest is let go, the sharp one it is made from kept
    @Test
    fun aBlurOfAWidthNotDrawnLatelyIsLetGo() {
        use("p.png" to intArrayOf(1000, 800))
        val l = pictured()
        val wide = ThemeAssets.picture(l, false, 8, 1000, 2f)!!
        ThemeAssets.picture(l, false, 8, 500, 2f)
        ThemeAssets.picture(l, false, 8, 700, 2f)
        assertSame("three widths kept", wide, ThemeAssets.picture(l, false, 8, 1000, 2f))
        ThemeAssets.picture(l, false, 8, 300, 2f)
        ThemeAssets.picture(l, false, 8, 400, 2f)
        ThemeAssets.picture(l, false, 8, 600, 2f)
        // three widths asked for since 1000 was: let go, made anew
        assertTrue(wide !== ThemeAssets.picture(l, false, 8, 1000, 2f))
        assertEquals("the sharp one decoded once", listOf("p.png" to 1), decoder.decoded)
    }

    // a blur still being made, a view waiting on it, is never the one let go past the last few widths:
    // two views drawing at two widths at once would else let each other's go and blur again and again
    @Test
    fun aBlurAViewWaitsOnIsNotLetGoForWidthsDrawnSince() {
        use("p.png" to intArrayOf(1000, 800))
        val l = pictured()
        ThemeAssets.picture(l, false, 0, 1000, 2f)
        var told = 0
        assertNull(ThemeAssets.pictureOrLater(l, false, 8, 900, 2f) { told++ })
        // three widths made at once since: the oldest asked for is the one waited on
        ThemeAssets.picture(l, false, 8, 300, 2f)
        ThemeAssets.picture(l, false, 8, 400, 2f)
        ThemeAssets.picture(l, false, 8, 600, 2f)
        runQueued()
        assertEquals(1, told)
        // still kept: in at once, nothing queued to make it again
        assertNotNull(ThemeAssets.pictureOrLater(l, false, 8, 900, 2f) {}?.bitmap)
        assertTrue("nothing queued", queued.isEmpty())
    }

    // each frame a view waiting on a picture draws once queued another fill, each found it done
    @Test
    fun framesDrawnBeforeAPictureIsInQueueOneFill() {
        use("p.png" to intArrayOf(1000, 800))
        val l = pictured()
        var told = 0
        repeat(5) { assertNull(ThemeAssets.pictureOrLater(l, false, 0, 1000, 2f) { told++ }) }
        assertEquals(1, queued.size)
        runQueued()
        assertEquals("every frame's view told", 5, told)
    }

    // pins the outcome, not how: a theme drawn again after its queued fill was skipped (another theme
    // drawn before it ran) gets a fill and its view told, never left blank waiting on the skipped one
    @Test
    fun aFillSkippedForAnotherThemeIsQueuedAgainOnceTheThemeIsBack() {
        use("p.png" to intArrayOf(1000, 800), "q.png" to intArrayOf(1000, 800))
        val a = pictured()
        val b = a.copy(picture = a.picture!!.copy(portrait = Crop("q.png", null, 0)))
        var told = 0
        assertNull(ThemeAssets.pictureOrLater(a, false, 0, 1000, 2f) { told++ })
        ThemeAssets.retain(b)
        assertNotNull(ThemeAssets.picture(b, false, 0, 1000, 2f))
        runQueued()
        assertFalse(decoder.decoded.any { it.first == "p.png" })
        assertEquals(0, told)
        ThemeAssets.retain(a)
        assertNull(ThemeAssets.pictureOrLater(a, false, 0, 1000, 2f) { told++ })
        assertEquals(1, queued.size)
        runQueued()
        assertEquals(1, told)
    }

    @Test
    fun filesLetGoAsChangedAreANewGenerationAnotherThemeDrawnIsNot() {
        use("p.png" to intArrayOf(1000, 800), "q.png" to intArrayOf(1000, 800))
        val g = ThemeAssets.generation
        ThemeAssets.picture(pictured(), false, 0, 1000, 2f)
        ThemeAssets.retain(pictured().let { it.copy(picture = it.picture!!.copy(portrait = Crop("q.png", null, 0))) })
        assertEquals(g, ThemeAssets.generation)
        ThemeAssets.clear()
        assertEquals(g + 1, ThemeAssets.generation)
    }

    @Test
    fun aPicturePlacedIsAskedForAgainOnceTheFilesChanged() {
        use("p.png" to intArrayOf(1000, 800))
        val base = View(RuntimeEnvironment.getApplication()).apply { layout(0, 0, 1000, 800) }
        val host = PictureHost(pictured(), base, false)
        val paint = PicturePaint()
        // decoded on the decode thread, then drawn
        host.paint(base, 0, 100, paint)
        runQueued()
        assertNotNull(host.paint(base, 0, 100, paint))
        host.paint(base, 0, 100, paint)
        assertEquals("placed once", 1, decoder.decoded.size)
        // imported again under its name, the theme itself the same: its new file decoded
        ThemeAssets.clear()
        host.paint(base, 0, 100, paint)
        runQueued()
        assertNotNull(host.paint(base, 0, 100, paint))
        assertEquals(2, decoder.decoded.size)
    }

    @Test
    fun aThumbnailThatDoesNotReadIsTriedAgain() {
        use()
        val got = mutableListOf<Bitmap?>()
        ThemeAssets.thumbnail(pictured(), 300, 1000, 2f) { got += it }
        runQueued()
        assertEquals(listOf<Bitmap?>(null), got)
        // its file there now (written as it was asked, say): not none kept
        use("p.png" to intArrayOf(600, 600))
        ThemeAssets.thumbnail(pictured(), 300, 1000, 2f) { got += it }
        runQueued()
        assertEquals(300, got[1]!!.width)
    }

    // the bug: a theme picture no prefetch made (a floating keyboard's width) was decoded and blurred
    // in draw on the main thread, a frame held up by a picture of 4096
    @Test
    fun aPictureNotDecodedAheadIsDecodedOnTheDecodeThreadNotTheDrawingOne() {
        use("p.png" to intArrayOf(1000, 800))
        val l = pictured(blur = 8)
        var told = 0
        // a width no prefetch made (a floating keyboard's): the sharp one queued, nothing decoded on the drawing thread
        assertNull(ThemeAssets.pictureOrLater(l, false, 8, 600, 2f) { told++ })
        assertEquals(0, decoder.decoded.size)
        runQueued()
        assertEquals(1, decoder.decoded.size)
        assertEquals(1, told)
        // its blur next: queued too, not made here
        assertNull(ThemeAssets.pictureOrLater(l, false, 8, 600, 2f) { told++ })
        runQueued()
        assertEquals(2, told)
        assertNotNull(ThemeAssets.pictureOrLater(l, false, 8, 600, 2f) { told++ }!!.bitmap)
        assertEquals("in: nothing queued", 2, told)
    }
}
