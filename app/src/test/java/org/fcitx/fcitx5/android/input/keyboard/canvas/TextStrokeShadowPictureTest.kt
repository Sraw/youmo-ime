/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.TextShadow
import org.fcitx.fcitx5.android.scene.TextStroke
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.engine.Texts
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
 * 060-render-p3 T4a: a theme's `stroke` and `text-shadow` as [ScenePainter] draws them, the qwerty's text
 * over a picture (what a theme of see-through keys shows): the outline round the glyphs under their
 * fill, the shadow under both, and neither without them. The picture is written to
 * build/t4a/stroke-shadow.png (and stroke-shadow-none.png) to be looked at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// the layouts are the app's (SceneLayouts reads its prefs)
@Config(application = FcitxApplication::class)
class TextStrokeShadowPictureTest {
    private val context = RuntimeEnvironment.getApplication()
    private val look = ThemePreset.PixelLight.look
    private val density = 2.625
    private val env = Env(
        1080.0, 720.0, density,
        texts = Texts(imeName = "拼音", alts = "qwertyuiop".zip("1234567890").associate { (k, v) -> "$k" to "$v" }),
    )

    // grey bands and rings: nothing of the text's red, white or blue in it
    private fun picture(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until BANDS) {
            p.color = Color.rgb(60 + i * 12, 60 + i * 12, 60 + i * 12)
            c.drawRect(0f, i * h / BANDS.toFloat(), w.toFloat(), (i + 1) * h / BANDS.toFloat(), p)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 18f
        p.color = Color.rgb(150, 150, 150)
        for (r in 80..600 step 120) c.drawCircle(w / 2f, h / 2f, r.toFloat(), p)
    }

    private fun drawn(styles: String): Pair<Bitmap, List<Op.Text>> {
        val theme = ThemeReader.theme(LookTheme.xml(look, "t4a", false).replace("</theme>", "$styles\n</theme>")).getOr { error("$it") }
        val fonts = SceneFonts(look)
        val layout = requireNotNull(SceneLayouts.of(context, "qwerty"))
        val scene = SceneBuilder(theme, env, AndroidTextMeasurer(fonts)).build(layout.layout, layout.fragments).getOr { error("$it") }
        val b = picture(scene.width.roundToInt(), scene.height.roundToInt())
        // the keys' text and icons only: their faces see-through, the picture under them
        val ops = scene.keys.flatMap { k -> k.ops(false).filter { it is Op.Text || it is Op.Icon } }
        ScenePainter(View(context), look, BoardImages.NONE, fonts).draw(Canvas(b), ops)
        return b to ops.filterIsInstance<Op.Text>()
    }

    private fun save(b: Bitmap, name: String) {
        val dir = File("build/t4a").apply { mkdirs() }
        File(dir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // pixels about [op]'s glyphs (its advance across, its size up and down) that [what] holds for
    private fun count(b: Bitmap, op: Op.Text, what: (Int) -> Boolean): Int {
        val reach = (4 * density).roundToInt()
        val w = AndroidTextMeasurer(SceneFonts(look)).measure(op.text, op.font, op.weight, op.size).advance
        val left = (op.x - reach).roundToInt().coerceAtLeast(0)
        val right = (op.x + w + reach).roundToInt().coerceAtMost(b.width - 1)
        val top = (op.baseline - op.size - reach).roundToInt().coerceAtLeast(0)
        val bottom = (op.baseline + op.size / 2 + reach).roundToInt().coerceAtMost(b.height - 1)
        var n = 0
        for (y in top..bottom) for (x in left..right) if (what(b.getPixel(x, y))) n++
        return n
    }

    private fun red(c: Int) = Color.red(c) > 180 && Color.green(c) < 90 && Color.blue(c) < 90

    private fun blue(c: Int) = Color.blue(c) > Color.red(c) + 50 && Color.blue(c) > Color.green(c) + 50

    private fun white(c: Int) = Color.red(c) > 235 && Color.green(c) > 235 && Color.blue(c) > 235

    @Test
    fun anOutlineAndAShadowOnAPicture() {
        val (b, texts) = drawn(
            "<style select=\"key, key label, key mark\" color=\"#ffffffff\" stroke=\"1.5dp #ffff0000\" text-shadow=\"2dp 3dp 3dp #ff0000ff\"/>",
        )
        save(b, "stroke-shadow.png")
        val q = texts.first { it.text == "q" }
        assertTrue("the outline and the shadow carried", q.stroke != null && q.shadow != null)
        assertTrue("the outline round q", count(b, q, ::red) > 40)
        assertTrue("its fill over the outline", count(b, q, ::white) > 40)
        assertTrue("its shadow under both", count(b, q, ::blue) > 40)
        // the space's name too, its Han characters
        val name = texts.first { it.text == "拼音" }
        assertTrue(count(b, name, ::red) > 40 && count(b, name, ::blue) > 40)
        // none set: neither
        val (plain, plainTexts) = drawn("<style select=\"key, key label, key mark\" color=\"#ffffffff\"/>")
        save(plain, "stroke-shadow-none.png")
        val bare = plainTexts.first { it.text == "q" }
        assertEquals(0, count(plain, bare, ::red))
        assertEquals(0, count(plain, bare, ::blue))
        assertTrue(count(plain, bare, ::white) > 40)
    }

    @Test
    fun aSharpShadowIsDrawnToo() {
        // setShadowLayer of no blur draws none: the painter draws the glyphs again, moved
        val (b, texts) = drawn("<style select=\"key, key label, key mark\" color=\"#ffffffff\" text-shadow=\"2dp 2dp 0 #ff0000ff\"/>")
        assertTrue(count(b, texts.first { it.text == "q" }, ::blue) > 40)
    }

    @Test
    fun paintMeasuresLetterSpacing() {
        // Paint's own: as the engine's arithmetic (TextMeasurer's default, the web page's) has it, near enough,
        // and kept apart from the unspaced
        val m = AndroidTextMeasurer(SceneFonts(look))
        val arithmetic = TextMeasurer { text, font, weight, size -> m.measure(text, font, weight, size) }
        val plain = m.measure("abc", "system", 400, 60.0)
        val spaced = m.measure("abc", "system", 400, 60.0, 0.2)
        val reckoned = arithmetic.measure("abc", "system", 400, 60.0, 0.2)
        // the advance: Android 10 leaves the last character's space off it (its ink the same), later ones keep it
        assertTrue("${spaced.advance} of ${reckoned.advance}", spaced.advance in reckoned.advance - 0.2 * 60 - 1..reckoned.advance + 1)
        assertEquals(reckoned.ink.left, spaced.ink.left, 1.0)
        assertEquals(reckoned.ink.right, spaced.ink.right, 1.0)
        assertEquals(2, m.measured)
        assertEquals(plain, m.measure("abc", "system", 400, 60.0, 0.0))
        assertEquals(2, m.measured)
    }

    @Test
    fun aTransparentTextsShadowIsDrawn() {
        // as CSS has it: the fill not there, its shadow is (setShadowLayer's took the fill's alpha: none)
        val (b, texts) = drawn("<style select=\"key, key label, key mark\" color=\"#00000000\" text-shadow=\"2dp 2dp 2dp #ff0000ff\"/>")
        val q = texts.first { it.text == "q" }
        assertTrue(count(b, q, ::blue) > 40)
        assertEquals(0, count(b, q, ::white))
    }

    private fun ops(ops: List<Op>): Bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).also { b ->
        b.eraseColor(Color.BLACK)
        ScenePainter(View(context), look, BoardImages.NONE, SceneFonts(look)).draw(Canvas(b), ops)
    }

    private fun Bitmap.pixels(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    // a text outlined and blurred-shadowed, then one plain: the plain one as drawn alone (no mask, no
    // shadow layer, no stroke kept from the one before)
    @Test
    fun nothingOfAnOpIsLeftForTheNext() {
        val white = Argb(Color.WHITE)
        val plain = Op.Text("W", 220.0, 120.0, "system", 400, 80.0, white)
        val fancy = plain.copy(
            "A", x = 20.0, stroke = TextStroke(6.0, Argb(Color.RED)), shadow = TextShadow(4.0, 4.0, 6.0, Argb(Color.BLUE)),
        )
        val both = ops(listOf(fancy, plain)).pixels()
        val alone = ops(listOf(plain)).pixels()
        val w = 400
        for (y in 0 until 200) for (x in 200 until w) assertEquals("($x, $y)", alone[y * w + x], both[y * w + x])
    }

    // a text in two runs with a thick outline: the second's outline not over the first's fill, as Paint.content
    // orders them (every outline, then every fill); one run after the other would cover it
    @Test
    fun runsOutlinedKeepEachFill() {
        val m = AndroidTextMeasurer(SceneFonts(look))
        val stroke = TextStroke(20.0, Argb(Color.RED))
        val a = Op.Text("H", 20.0, 120.0, "system", 400, 80.0, Argb(Color.WHITE), stroke = stroke)
        val b = a.copy(x = 20.0 + m.measure("H", "system", 400, 80.0).advance)
        fun outline(t: Op.Text) = t.copy(color = Argb(0))
        fun fill(t: Op.Text) = t.copy(stroke = null)
        val twoPass = ops(listOf(outline(a), outline(b), fill(a), fill(b)))
        val oneByOne = ops(listOf(outline(a), fill(a), outline(b), fill(b)))
        save(twoPass, "runs-two-pass.png")
        save(oneByOne, "runs-one-by-one.png")
        val alone = ops(listOf(outline(a), fill(a))).pixels()
        val wFill = ops(listOf(fill(b))).pixels()
        val two = twoPass.pixels()
        val one = oneByOne.pixels()
        // the first H's fill (where the second's is not): all there two-pass, a part of it under the second's outline
        // one run by one (an outline wider than an H's sides)
        val aFill = alone.indices.filter { white(alone[it]) && !white(wFill[it]) }
        assertTrue(aFill.size > 40)
        assertEquals(aFill.size, aFill.count { white(two[it]) })
        assertTrue("${aFill.count { white(one[it]) }} of ${aFill.size}", aFill.count { white(one[it]) } < aFill.size)
    }

    private companion object {
        const val BANDS = 8
    }
}
