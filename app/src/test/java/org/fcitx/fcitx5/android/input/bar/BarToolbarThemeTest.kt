/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import android.graphics.drawable.StateListDrawable
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.TextFormatFlag
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.candidates.SceneBar
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneThemes
import org.fcitx.fcitx5.android.input.preedit.PreeditUi
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 060-render-p3 T6b in the app: the bar's height (`bar height=`) and how the keyboard's height is shared
 * with it, docked and floating; the toolbar's buttons and the code being typed as the theme has them; a
 * theme setting none of it as before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class BarToolbarThemeTest {

    @get:Rule
    val prefs = PrefsRule()

    private val realSource = SceneThemes.source

    @After
    fun tearDown() {
        SceneThemes.source = realSource
    }

    // a theme 3.0's file and [styles] in it
    private fun styled(vararg styles: String) {
        SceneThemes.source = { look, name, dark -> LookTheme.xml(look, name, dark).replace("</theme>", styles.joinToString("\n", postfix = "\n</theme>")) }
    }

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    // the bar's and the keys' heights, px, the keyboard shown
    private fun heights(floating: Boolean): Pair<Int, Int> {
        prefs.set(ThemeManager.prefs.floatingKeyboard, floating)
        return KeyboardHarness().use { h ->
            h.start()
            h.after(1000)
            h.inputView.kawaiiBar.view.height to KeyboardHarness.find<TextKeyboard>(h.inputView)!!.height
        }
    }

    @Test
    fun noneSetIsTheBarOfBefore() {
        val theme = ThemeManager.activeTheme
        assertEquals(100.0, SceneBar.heightPercent(), 0.0)
        assertEquals(CandidateBarHeight.dp(ThemeManager.prefs.candidateTextSize.getValue()), KawaiiBarComponent.heightDp())
        val button = ToolButton(ctx, R.drawable.ic_clipboard, theme)
        assertFalse("the press highlight's circle, as ever", button.background is StateListDrawable)
        assertEquals(ThemeManager.shownLook(theme).palette.toolbarIconColor, button.iconTint)
        button.setOn(true)
        assertEquals(theme.accentKeyBackgroundColor, button.iconTint)
        val ui = PreeditUi(ctx, theme)
        ui.update(panel())
        val up = text(ui)
        assertEquals(theme.keyTextColor, up.currentTextColor)
        assertTrue("fcitx's own underline", (up.text as Spanned).getSpans(0, up.text.length, UnderlineSpan::class.java).isNotEmpty())
    }

    @Test
    fun theBarsHeightIsTheThemesTheKeysKeepTheirs() {
        val (bar, keys) = heights(floating = false)
        val (floatingBar, floatingKeys) = heights(floating = true)
        styled("<style select=\"bar\" height=\"120%\"/>")
        assertEquals(120.0, SceneBar.heightPercent(), 0.0)
        assertEquals(48, KawaiiBarComponent.heightDp())
        // docked: the bar taller, the keys as they were (the cap not reached), the keyboard so much taller
        val (tall, tallKeys) = heights(floating = false)
        assertEquals(dp(48), tall)
        assertEquals(dp(40), bar)
        assertEquals(keys, tallKeys)
        // floating: the same
        val (floatingTall, floatingTallKeys) = heights(floating = true)
        assertEquals(dp(48), floatingTall)
        assertEquals(dp(40), floatingBar)
        assertEquals(floatingKeys, floatingTallKeys)
    }

    @Test
    fun theToolbarAndTheCodeAreTheThemes() {
        styled(
            "<style select=\"toolbar\" fill=\"#ffeeeeee\" radius=\"8dp\" icon-color=\"#ff445566\"/>",
            "<style select=\"toolbar:selected\" fill=\"#ff112233\" icon-color=\"#ffffffff\"/>",
            "<style select=\"preedit\" color=\"#ff112233\" underline=\"2dp #ffff0000\" cursor-color=\"#ff00ff00\"/>",
        )
        val theme = ThemeManager.activeTheme
        val button = ToolButton(ctx, R.drawable.ic_clipboard, theme)
        assertTrue(button.background is StateListDrawable)
        assertEquals(0xFF445566.toInt(), button.iconTint)
        val off = button.background
        button.setOn(true)
        assertEquals(0xFFFFFFFF.toInt(), button.iconTint)
        assertTrue("on, its own fill", button.background !== off)
        val ui = PreeditUi(ctx, theme)
        ui.update(panel())
        val up = text(ui)
        assertEquals(0xFF112233.toInt(), up.currentTextColor)
        val s = up.text as Spanned
        assertTrue("drawn by the view, not the span", s.getSpans(0, s.length, UnderlineSpan::class.java).isEmpty())
        assertEquals(1, s.getSpans(0, s.length, PreeditUi.ThemedUnderline::class.java).size)
        // no underline at all: none drawn
        styled("<style select=\"preedit\" underline=\"none\"/>")
        val none = PreeditUi(ctx, ThemeManager.activeTheme).also { it.update(panel()) }
        val t = text(none).text as Spanned
        assertTrue(t.getSpans(0, t.length, UnderlineSpan::class.java).isEmpty() && t.getSpans(0, t.length, PreeditUi.ThemedUnderline::class.java).isEmpty())
    }

    // "ni hao", underlined as fcitx asks, its cursor in the middle
    private fun panel(): HostEvent.InputPanel {
        val empty = FormattedText(emptyArray(), IntArray(0), -1)
        return HostEvent.InputPanel(FormattedText(arrayOf("ni hao"), intArrayOf(TextFormatFlag.Underline.flag), 2), empty, empty)
    }

    private fun text(ui: PreeditUi): TextView = (ui.root as android.view.ViewGroup).getChildAt(0) as TextView
}
