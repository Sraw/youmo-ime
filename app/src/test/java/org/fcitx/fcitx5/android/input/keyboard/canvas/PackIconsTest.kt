/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.SCREENSHOT_SDK
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.io.path.createTempDirectory

/** A theme's `<icon>` drawn in place of the app's (060-theme-sounds-icons §2): its colours, its fit, a broken file. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, sdk = [SCREENSHOT_SDK])
class PackIconsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val look = TestThemes.PixelLight.look
    private val dir = createTempDirectory("pack-icons").toFile()
    private val name = SceneIcons.names.sorted().first { SceneIcons.of(it) != null }

    private val runner = ThemeAssets.runner
    private val poster = ThemeAssets.poster

    @Before
    fun atOnce() {
        ThemeAssets.runner = { it.run() }
        ThemeAssets.poster = { it.run() }
    }

    @After
    fun forget() {
        ThemeAssets.runner = runner
        ThemeAssets.poster = poster
        ScenePainter.forgetIcons()
        dir.deleteRecursively()
    }

    private fun icon(svg: String, file: String = "i.svg"): PackIcons = PackIcons(mapOf(name to File(dir, file).apply { writeText(svg) }.path))

    // the icon [name] drawn in [tint] in a 24 px box at 0, 0 of a 24 × 24 bitmap
    private fun drawn(icons: PackIcons, tint: Argb = Argb(Color.WHITE), painter: ScenePainter = painter(icons)): Bitmap {
        val b = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        painter.draw(Canvas(b), listOf(Op.Icon(name, Box(0.0, 0.0, SIDE.toDouble(), SIDE.toDouble()), tint)))
        return b
    }

    private fun painter(icons: PackIcons) = ScenePainter(View(context), look, BoardImages.NONE, SceneFonts(look), icons)

    // read off the main thread (a frame of the largest takes far past 16 ms): the app's icon till it is in, then drawn again
    @Test
    fun anIconIsReadOnTheDecodeThreadTheAppsDrawnTillThen() {
        val queued = ArrayList<Runnable>()
        ThemeAssets.runner = { queued += it }
        val icons = icon("""<svg><path d="M0 0h12v24h-12z" fill="#00f"/></svg>""")
        val app = drawn(PackIcons.EMPTY)
        var asked = 0
        val view = object : View(context) {
            override fun invalidate() {
                asked++
                super.invalidate()
            }
        }
        val painter = ScenePainter(view, look, BoardImages.NONE, SceneFonts(look), icons)
        assertTrue(app.sameAs(drawn(icons, painter = painter)))
        assertTrue(painter.missed)
        assertEquals(1, queued.size)
        // asked again while it is read: not read twice
        drawn(icons, painter = painter)
        assertEquals(1, queued.size)
        val before = asked
        queued.single().run()
        // the waiting painter's view told to draw again, and that painter draws the icon now
        assertTrue(asked > before)
        assertEquals(Color.BLUE, drawn(icons, painter = painter).getPixel(4, 12))
    }

    // a pack's files written over while one is read: its waiting view drawn again, the stale read not kept
    @Test
    fun aForgetWhileAnIconIsReadRedrawsItsViewAndDropsTheRead() {
        val queued = ArrayList<Runnable>()
        ThemeAssets.runner = { queued += it }
        val icons = icon("""<svg><path d="M0 0h12v24h-12z" fill="#00f"/></svg>""")
        var asked = 0
        val view = object : View(context) {
            override fun invalidate() {
                asked++
                super.invalidate()
            }
        }
        val painter = ScenePainter(view, look, BoardImages.NONE, SceneFonts(look), icons)
        drawn(icons, painter = painter)
        assertEquals(1, queued.size)
        val before = asked
        ScenePainter.forgetIcons()
        assertTrue(asked > before)
        queued.single().run()
        assertTrue(icons.outline(icons.file(name)!!) == null)
        // read afresh, not the one read before the forget
        assertEquals(2, queued.size)
    }

    @Test
    fun aPathsOwnColourIsKeptAndTheTintsAlphaApplied() {
        // the left half in its own blue (CSS's #rgba: alpha last), the right in the tint
        val icons = icon("""<svg viewBox="0 0 24 24"><path d="M0 0h12v24h-12z" fill="#00ff"/><path d="M12 0h12v24h-12z"/></svg>""")
        val b = drawn(icons, Argb.of(HALF, 255, 0, 0))
        val left = b.getPixel(4, 12)
        val right = b.getPixel(20, 12)
        assertEquals(listOf(0, 0, 255), listOf(Color.red(left), Color.green(left), Color.blue(left)))
        assertEquals(listOf(255, 0, 0), listOf(Color.red(right), Color.green(right), Color.blue(right)))
        assertTrue("${Color.alpha(left)}", Color.alpha(left) in HALF - 2..HALF + 2)
        assertTrue("${Color.alpha(right)}", Color.alpha(right) in HALF - 2..HALF + 2)
    }

    @Test
    fun aWideIconIsCentredNotStretched() {
        val b = drawn(icon("""<svg viewBox="0 0 48 24"><path d="M0 0h48v24h-48z"/></svg>"""))
        assertEquals(0, Color.alpha(b.getPixel(12, 2)))
        assertEquals(255, Color.alpha(b.getPixel(12, 12)))
        assertEquals(0, Color.alpha(b.getPixel(12, 21)))
    }

    @Test
    fun aBrokenOrGoneFileDrawsTheAppsIcon() {
        val app = drawn(PackIcons.EMPTY)
        assertTrue("the app's icon draws something", (0 until SIDE * SIDE).any { Color.alpha(app.getPixel(it % SIDE, it / SIDE)) > 0 })
        fun same(b: Bitmap) = assertTrue(app.sameAs(b))
        same(drawn(icon("""<svg><script/></svg>""", "bad.svg")))
        same(drawn(PackIcons(mapOf(name to File(dir, "gone.svg").path))))
        // over 64 KB: not read at all
        same(drawn(icon("""<svg><path d="M0 0h1v1z"/></svg>""" + " ".repeat(64 * 1024), "big.svg")))
        assertNull(PackIcons.read(File(dir, "gone.svg").path))
    }

    @Test
    fun anIconIsReadOnceAndForgottenWithThePainters() {
        val icons = icon("""<svg><path d="M0 0h24v24h-24z"/></svg>""")
        val file = icons.file(name)!!
        val first = icons.outline(file)
        assertTrue(first === icons.outline(file))
        ScenePainter.forgetIcons()
        assertTrue(first !== icons.outline(file))
    }

    private companion object {
        const val SIDE = 24
        const val HALF = 128
    }
}
