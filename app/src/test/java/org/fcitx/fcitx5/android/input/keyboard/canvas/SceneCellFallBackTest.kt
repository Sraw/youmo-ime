/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.app.Activity
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.KeyboardCanvasViewTest
import org.fcitx.fcitx5.android.scene.Op
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import timber.log.Timber
import kotlin.math.roundToInt

/** Popups and cells draw in the keys' theme: the built-in of the same lightness where the engine makes none or refused the keys; a faded label's every piece faded. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = KeyboardCanvasViewTest.PORTRAIT)
class SceneCellFallBackTest {

    private val context = RuntimeEnvironment.getApplication()
    @get:Rule
    val themes = SceneThemesRule()
    private val realFallback = SceneLayouts.fallback
    private val builtIn = ThemeManager.builtin("pixel#light")

    @After
    fun tearDown() {
        SceneThemes.forgetAll()
        SceneLayouts.fallback = realFallback
        SceneBoard.shown(null)
    }

    // a name of its own: the fall backs are logged once a name and look, a process long
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

    // one keyboard's keys refused by the engine: the popups and cells follow the keyboard shown as its keys fall back and come back
    @Test
    fun aKeyboardsKeysRefusedThePopupsAndCellsFollowTheOneShown() {
        val t = custom()
        // a pack's refused draws the app's of its id instead (SceneBoard.ownLayout); the app's refused, the built-in theme draws
        SceneLayouts.fallback = { ctx ->
            val app = BuiltInLayouts(ctx)
            object : LayoutSource {
                override val key = "refused-qwerty"

                override fun text(id: String) = if (id == "qwerty") TOO_WIDE.replace("ID", id) else app.text(id)
            }
        }
        fun board(id: String, refused: Boolean = false): SceneBoard {
            val layout = if (refused) SceneLayouts.builtIn(context, id)!! else SceneLayouts.of(context, id)!!
            return SceneBoard(context, t, layout) { look, _, w, h -> SceneEnvs.of(View(context), look, false, w, h, Input()) }
        }
        fun preview(id: String, refused: Boolean = true): SceneBoard {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val layout = if (refused) SceneLayouts.builtIn(activity, id)!! else SceneLayouts.of(activity, id)!!
            return SceneBoard(activity, t, layout) { look, _, w, h -> SceneEnvs.of(View(activity), look, false, w, h, Input()) }
        }
        fun draw(b: SceneBoard) = b.view(BoardImages.NONE, key = { null }).apply { layout(0, 0, 1080, 700) }.scene
        assertNotNull(draw(board("k18")))
        assertSame(t, SceneBoard.drawnTheme(t))
        // a refused keyboard in an activity (the settings' preview) is not the input method's: its popups and cells keep their theme
        assertNull(draw(preview("qwerty")))
        assertSame(t, SceneBoard.drawnTheme(t))
        assertSame(t, SceneBoard.drawnTheme(t, "qwerty"))
        val kept = cell(t)
        assertTrue(kept.drawn().isNotEmpty())
        assertEquals(t.name, kept.drawsIn)
        val keptScene = kept.scene
        assertNull(draw(board("qwerty", refused = true)))
        assertEquals(builtIn.name, SceneBoard.drawnTheme(t).name)
        assertNotNull(draw(preview("qwerty", refused = false)))
        assertEquals(builtIn.name, SceneBoard.drawnTheme(t, "qwerty").name)
        assertTrue(PopupScenes(View(context), t).ready)
        assertTrue(cell(t).drawn().isNotEmpty())
        assertTrue(kept.drawn().isNotEmpty())
        assertEquals(builtIn.name, kept.drawsIn)
        assertNotSame(keptScene, kept.scene)
        assertNotNull(draw(board("k18")))
        assertSame(t, SceneBoard.drawnTheme(t))
        assertEquals(t.name, kept.drawsIn)
        draw(board("qwerty", refused = true))
        assertEquals(builtIn.name, SceneBoard.drawnTheme(t).name)
        assertNotNull(draw(board("qwerty")))
        assertSame(t, SceneBoard.drawnTheme(t))
        draw(board("qwerty", refused = true))
        SceneThemes.forgetAll()
        assertSame(t, SceneBoard.drawnTheme(t))
    }

    @Test
    fun aThemeThatThrowsIsNone() {
        val t = custom()
        SceneThemes.source = { _, _, _ -> error("broken") }
        assertNull(SceneThemes.of(ThemeManager.shownLook(t), t.name, false))
    }

    @Test
    fun theBuiltInRefusedTooNothing() {
        val t = custom()
        SceneThemes.source = { _, _, _ -> "<theme/>" }
        assertFalse(PopupScenes(View(context), t).ready)
        assertEquals(emptyList<Op>(), cell(t).drawn())
    }

    @Test
    fun aFadedLabelItsOutlineAndShadowToo() {
        val t = custom()
        val styles = "<style select=\"key, key label, key mark\" color=\"#ffffffff\" stroke=\"1.5dp #ffff0000\" text-shadow=\"2dp 3dp 3dp #ff0000ff\"/>"
        themes.styled(styles)
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
        // read, but the engine places none of it (Parsed.Failed)
        const val TOO_WIDE = """<keyboard xmlns="https://youmo-ime/layout/1" id="ID" name="x" family="number-row" columns="1">
  <row><key id="a" label="a" send="sym:a" width="2"/></row>
</keyboard>"""
    }
}
