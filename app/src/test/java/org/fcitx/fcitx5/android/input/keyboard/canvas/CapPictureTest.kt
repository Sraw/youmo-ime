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
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.Shape.Path.Op.Close
import org.fcitx.fcitx5.android.scene.Shape.Path.Op.LineTo
import org.fcitx.fcitx5.android.scene.Shape.Path.Op.MoveTo
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.getOr
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

/**
 * 060-render-p3 T4b: a key of a `path(…)` with an `inner-shadow` and a `depth` as [ScenePainter] draws
 * them: the face the path (its box's corners not drawn), the inner shadow inside it at the top (its
 * offset down) and nowhere outside it, the band of `depth-color` under the face. The picture is written
 * to build/t4b/cap.png to be looked at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// the layouts are the app's (SceneLayouts reads its prefs)
@Config(application = FcitxApplication::class)
class CapPictureTest {
    private val context = RuntimeEnvironment.getApplication()
    private val look = ThemePreset.PixelLight.look
    private val density = 2.625
    private val env = Env(1080.0, 720.0, density)

    // a pentagon, a house: its box's top corners outside it
    private val styles = "<style select=\"key, key.letter, key.function\" fill=\"#ffffffff\" shadow=\"none\" " +
        "shape=\"path(M.5 0 L1 .35 L1 1 L0 1 L0 .35 Z)\" depth=\"4dp\" depth-color=\"#ffff0000\" inner-shadow=\"0 3dp 3dp #ff0000ff\"/>"

    private fun red(c: Int) = Color.red(c) > 180 && Color.green(c) < 90 && Color.blue(c) < 90

    private fun blue(c: Int) = Color.blue(c) > Color.red(c) + 50 && Color.blue(c) > Color.green(c) + 50

    private fun white(c: Int) = Color.red(c) > 235 && Color.green(c) > 235 && Color.blue(c) > 235

    @Test
    fun aPathKeyWithAnInnerShadowAndADepth() {
        val theme = ThemeReader.theme(LookTheme.xml(look, "t4b", false).replace("</theme>", "$styles\n</theme>")).getOr { error("$it") }
        val fonts = SceneFonts(look)
        val layout = requireNotNull(SceneLayouts.of(context, "qwerty"))
        val scene = SceneBuilder(theme, env, AndroidTextMeasurer(fonts)).build(layout.layout, layout.fragments).getOr { error("$it") }
        val b = Bitmap.createBitmap(scene.width.roundToInt(), scene.height.roundToInt(), Bitmap.Config.ARGB_8888)
        b.eraseColor(BACKGROUND)
        // the faces only: the letters would be white's and blue's neighbours
        val ops = scene.keys.flatMap { k -> k.ops(false).filter { it !is Op.Text && it !is Op.Icon } }
        ScenePainter(View(context), look, BoardImages.NONE, fonts).draw(Canvas(b), ops)
        val dir = File("build/t4b").apply { mkdirs() }
        File(dir, "cap.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val g = scene.keys.first { it.id == "g" }
        val face = g.shape as Shape.Path
        val box = face.bounds
        val band = g.ops(false).filterIsInstance<Op.Fill>().first { it.color.value == 0xFFFF0000.toInt() }.shape.bounds
        assertTrue(g.ops(false).any { it is Op.InnerShadow })
        val cx = box.centerX.roundToInt()
        fun at(x: Double, y: Double) = b.getPixel(x.roundToInt(), y.roundToInt())
        // the face: white in its middle; its box's top corners not drawn (the path's, not a rectangle)
        assertTrue(white(at(box.centerX, box.top + box.height * 0.6)))
        assertEquals(BACKGROUND, at(box.left + 3, box.top + 3))
        assertEquals(BACKGROUND, at(box.right - 3, box.top + 3))
        // the inner shadow: inside, at the top (moved down 3 dp: the hole's top lower), not at the bottom
        val shadowed = (box.top.roundToInt() + 2..box.top.roundToInt() + 10).count { y -> blue(b.getPixel(cx, y)) }
        assertTrue("blue under the top", shadowed >= 5)
        val leftEdge = (box.left.roundToInt() + 1..box.left.roundToInt() + 4).count { x -> blue(b.getPixel(x, (box.top + box.height * 0.7).roundToInt())) }
        assertTrue("blue inside the left side too", leftEdge >= 2)
        assertTrue("none at the bottom", !blue(at(box.centerX, box.bottom - 2)))
        // and nowhere outside the face: above its top, beside its sides
        for (y in 2..5) assertEquals("above at $y", BACKGROUND, b.getPixel(cx, box.top.roundToInt() - y))
        for (x in 2..4) assertEquals("left at $x", BACKGROUND, at(box.left - x, box.top + box.height * 0.7))
        // the band under the face: red, as deep as the depth
        assertEquals(4 * density, band.bottom - box.bottom, 1e-9)
        assertTrue(red(at(box.centerX, box.bottom + 2 * density)))
        assertTrue(red(at(box.left + 4, box.bottom + 2 * density)))
        assertEquals(BACKGROUND, at(box.centerX, band.bottom + 2))
    }

    // a band under a translucent face shows only below it; a path crossing itself (a star, its middle inside by
    // the winding rule its fill is drawn by) has an inner shadow of no offset nowhere inside, its middle too
    @Test
    fun aBandLessItsFaceAndAStarsInnerShadowByItsFillsRule() {
        val b = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        b.eraseColor(BACKGROUND)
        val painter = ScenePainter(View(context), look, BoardImages.NONE, SceneFonts(look))
        val face = Shape.Rect(Box(10.0, 10.0, 90.0, 80.0))
        val band = face.moved(0.0, 10.0)
        val star = Shape.Path(
            listOf(MoveTo(150.0, 100.0), LineTo(180.0, 200.0), LineTo(100.0, 135.0), LineTo(200.0, 135.0), LineTo(120.0, 200.0), Close),
        )
        painter.draw(
            Canvas(b),
            listOf(
                Op.Fill(band, Argb(0xFFFF0000.toInt()), less = face),
                Op.Fill(face, Argb(0x40FFFFFF)),
                Op.Fill(star, Argb(0xFFFFFFFF.toInt())),
                Op.InnerShadow(star, star, Box(90.0, 90.0, 210.0, 210.0), 0.0, Argb(0xFF0000FF.toInt())),
            ),
        )
        val under = b.getPixel(50, 40)
        assertTrue("no red under the face: ${Integer.toHexString(under)}", Color.red(under) == Color.green(under))
        assertTrue(red(b.getPixel(50, 85)))
        val middle = b.getPixel(150, 150)
        assertTrue("the star's middle white: ${Integer.toHexString(middle)}", white(middle))
        assertTrue(white(b.getPixel(150, 120)))
    }

    private companion object {
        val BACKGROUND = Color.rgb(40, 40, 40)
    }
}
