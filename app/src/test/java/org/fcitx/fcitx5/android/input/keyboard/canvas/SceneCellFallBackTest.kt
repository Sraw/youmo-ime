/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.KeyboardCanvasViewTest
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.Part
import org.fcitx.fcitx5.android.scene.layout.KeyAttrs
import org.fcitx.fcitx5.android.scene.layout.Text
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * The popups and the panel's cells draw in the theme the keyboard's keys do (060-render-p2 U8, R20
 * F5821/F5919/F6564): the built-in of the same lightness where the engine makes no theme of the
 * theme or refused the keys' scene of it, logged once a look; nothing where the built-in is refused too.
 * A key cell's label faded (F5831): its every piece, its outline and shadow with it, nothing else.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = KeyboardCanvasViewTest.PORTRAIT)
class SceneCellFallBackTest {

    private val context = RuntimeEnvironment.getApplication()
    private val realSource = SceneThemes.source
    private val builtIn = ThemeManager.builtin("pixel#light")

    @After
    fun tearDown() {
        SceneThemes.source = realSource
        SceneThemes.forgetAll()
        SceneBoard.shown(null)
    }

    // a theme of its own name (the fall backs logged once a name and look, a process long), the built-in's look
    private fun custom(): Theme = Theme.Custom("fallback${System.nanoTime()}", false, builtIn.look)

    private fun cell(theme: Theme): SceneKeyCell = SceneKeyCell(context, theme, ownSize = true).apply {
        attrs = KeyAttrs(label = Text.Literal("a"))
        layout(0, 0, 160, 160)
    }

    private fun logged(block: () -> Unit): List<String> {
        val lines = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                lines += message
            }
        }
        Timber.plant(tree)
        try {
            block()
        } finally {
            Timber.uproot(tree)
        }
        return lines
    }

    // the theme not converted (F5821): popups ready, a cell drawn, both in the built-in; logged once for the look
    @Test
    fun aThemeNotConvertedPopupsAndCellsTheBuiltIns() {
        val t = custom()
        SceneThemes.source = { look, name, dark -> if (name == t.name) "<theme/>" else LookTheme.xml(look, name, dark) }
        val lines = logged {
            assertEquals(builtIn.name, SceneBoard.drawnTheme(t).name)
            assertTrue(PopupScenes(View(context), t).ready)
            assertTrue(cell(t).drawn().isNotEmpty())
        }
        assertEquals(lines.toString(), 1, lines.count { "no theme of ${t.name}" in it })
    }

    // the theme converted but one keyboard's keys refused by the engine (its scene made a layout at a
    // time; here a row wider than its keyboard, which the built-in is refused too): the popups and cells
    // follow the keyboard shown, its keys falling back and coming back as they are drawn (the popups
    // made before follow as they draw: PopupFollowsTheKeysTest)
    @Test
    fun aKeyboardsKeysRefusedThePopupsAndCellsFollowTheOneShown() {
        val t = custom()
        fun board(id: String, refused: Boolean = false): SceneBoard {
            val layout = if (refused) SceneLayout(id, LayoutReader.layout(TOO_WIDE.replace("ID", id)).let { it as? Parsed.Ok ?: error((it as Parsed.Failed).problems.toString()) }.value, emptyMap()) else SceneLayouts.of(context, id)!!
            return SceneBoard(context, t, layout) { look, _, w, h -> SceneEnvs.of(View(context), look, false, w, h, Input()) }
        }
        fun draw(b: SceneBoard) = b.view(BoardImages.NONE, key = { null }).apply { layout(0, 0, 1080, 700) }.scene
        assertNotNull(draw(board("k18")))
        assertSame(t, SceneBoard.drawnTheme(t))
        // a cell made before the keys fell back: it follows them as it draws, its scene made again
        val kept = cell(t)
        assertTrue(kept.drawn().isNotEmpty())
        assertEquals(t.name, kept.drawsIn)
        val keptScene = kept.scene
        assertNull(draw(board("qwerty", refused = true)))
        assertEquals(builtIn.name, SceneBoard.drawnTheme(t).name)
        assertTrue(PopupScenes(View(context), t).ready)
        assertTrue(cell(t).drawn().isNotEmpty())
        assertTrue(kept.drawn().isNotEmpty())
        assertEquals(builtIn.name, kept.drawsIn)
        assertNotSame(keptScene, kept.scene)
        // the other keyboard shown: its own keys, the popups' and cells' with them
        assertNotNull(draw(board("k18")))
        assertSame(t, SceneBoard.drawnTheme(t))
        assertEquals(t.name, kept.drawsIn)
        // the refused one's keys drawn: the built-in's again; made again in its own: the theme's
        draw(board("qwerty", refused = true))
        assertEquals(builtIn.name, SceneBoard.drawnTheme(t).name)
        assertNotNull(draw(board("qwerty")))
        assertSame(t, SceneBoard.drawnTheme(t))
        // a theme written again: the fall backs forgotten
        draw(board("qwerty", refused = true))
        SceneThemes.forgetAll()
        assertSame(t, SceneBoard.drawnTheme(t))
    }

    // writing the theme throws (F5919): no theme, no crash
    @Test
    fun aThemeThatThrowsIsNone() {
        val t = custom()
        SceneThemes.source = { _, _, _ -> error("broken") }
        assertNull(SceneThemes.of(ThemeManager.shownLook(t), t.name, false))
    }

    // the built-in refused too: nothing drawn, no popups
    @Test
    fun theBuiltInRefusedTooNothing() {
        val t = custom()
        SceneThemes.source = { _, _, _ -> "<theme/>" }
        assertFalse(PopupScenes(View(context), t).ready)
        assertEquals(emptyList<Op>(), cell(t).drawn())
    }

    // a faded label (F5831): every label piece's fill, outline and shadow at the alpha times it; any other text as it was
    @Test
    fun aFadedLabelItsOutlineAndShadowToo() {
        val t = custom()
        val styles = "<style select=\"key, key label, key mark\" color=\"#ffffffff\" stroke=\"1.5dp #ffff0000\" text-shadow=\"2dp 3dp 3dp #ff0000ff\"/>"
        SceneThemes.source = { look, name, dark -> LookTheme.xml(look, name, dark).replace("</theme>", "$styles\n</theme>") }
        val c = cell(t).apply { attrs = KeyAttrs(label = Text.Literal("a"), alt = Text.Literal("a")) }
        val full = c.drawn()
        c.labelAlpha = 0.4
        val faded = c.drawn()
        assertEquals(full.size, faded.size)
        val labels = full.filterIsInstance<Op.Text>().filter { it.part == Part.Label.xml }
        assertTrue(full.toString(), labels.isNotEmpty() && labels.all { it.stroke != null && it.shadow != null })
        fun a(alpha: Int) = (alpha * 0.4).roundToInt()
        full.zip(faded).forEach { (was, now) ->
            if (was is Op.Text && was.part == Part.Label.xml) {
                now as Op.Text
                assertEquals(a(was.color.alpha), now.color.alpha)
                assertEquals(a(was.stroke!!.color.alpha), now.stroke!!.color.alpha)
                assertEquals(a(was.shadow!!.color.alpha), now.shadow!!.color.alpha)
                assertEquals(was.copy(color = now.color, stroke = now.stroke, shadow = now.shadow), now)
            } else {
                assertEquals(was, now)
            }
        }
    }

    private companion object {
        // a row of 2 units on a keyboard of 1: read, but the engine places none of it (Parsed.Failed)
        const val TOO_WIDE = """<keyboard xmlns="https://youmo-ime/layout/1" id="ID" name="x" family="number-row" columns="1">
  <row><key id="a" label="a" send="sym:a" width="2"/></row>
</keyboard>"""
    }
}
