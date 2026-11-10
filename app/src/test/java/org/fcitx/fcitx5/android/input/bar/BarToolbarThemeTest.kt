/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.StaticLayout
import android.text.TextPaint
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.TextFormatFlag
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.utils.borderlessRippleDrawable
import org.fcitx.fcitx5.android.utils.circlePressHighlightDrawable
import org.fcitx.fcitx5.android.utils.motionSettings
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
    private fun heights(floating: Boolean, oneHanded: OneHanded = OneHanded.Off): Pair<Int, Int> {
        prefs.set(ThemeManager.prefs.floatingKeyboard, floating)
        prefs.set(ThemeManager.prefs.oneHanded, oneHanded)
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
        // the press highlight's circle, as ever: a ripple, or with animations off its plain circle
        val press = theme.keyPressHighlightColor
        val asEver = if (motionSettings().animates) borderlessRippleDrawable(press, 1) else circlePressHighlightDrawable(press)
        assertEquals(asEver::class, button.background::class)
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
        assertEquals(48, KawaiiBarComponent.heightDp(ctx))
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
        // one-handed: the same bar over the narrower keys
        val (oneHandedTall, oneHandedKeys) = heights(floating = false, oneHanded = OneHanded.Right)
        assertEquals(dp(48), oneHandedTall)
        assertEquals(keys, oneHandedKeys)
        // the least: a shorter bar, the keys as they were
        styled("<style select=\"bar\" height=\"80%\"/>")
        assertEquals(32, KawaiiBarComponent.heightDp(ctx))
        val (short, shortKeys) = heights(floating = false)
        assertEquals(dp(32), short)
        assertEquals(keys, shortKeys)
    }

    @Test
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun aSceneHasItsOwnHeight() {
        styled("<style select=\"bar\" height=\"120%\"/>", "<style select=\"@landscape bar\" height=\"90%\"/>")
        assertEquals(90.0, SceneBar.heightPercent(ctx), 0.0)
        assertEquals(36, KawaiiBarComponent.heightDp(ctx))
    }

    @Test
    fun theCapReachedTheKeysGiveTheBarItsRoom() {
        // 90% asked: the cap (70% of the screen, the bar in it) binds, so a taller bar is the keys' loss
        val keyboard = AppPrefs.getInstance().keyboard
        prefs.set(keyboard.keyboardHeightFollowTheme, false)
        prefs.set(keyboard.keyboardHeightPercent, 90)
        val keys = KeyboardArea.keysHeight(ctx, 0)
        assertTrue(keys < KeyboardArea.requestedHeight(ctx))
        styled("<style select=\"bar\" height=\"140%\"/>")
        assertEquals(keys - (dp(56) - dp(40)), KeyboardArea.keysHeight(ctx, 0))
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
        assertEquals(0xFF445566.toInt(), button.iconTint)
        // off: its fill of 8dp corners; pressed the overlay (3.0's press highlight) over it
        val press = theme.keyPressHighlightColor
        assertEquals(listOf(listOf(0xFFEEEEEE.toInt(), press), listOf(0xFFEEEEEE.toInt())), fills(button))
        assertEquals(dp(8).toFloat(), (states(button).last() as GradientDrawable).cornerRadii!![0], 1f)
        button.setOn(true)
        assertEquals(0xFFFFFFFF.toInt(), button.iconTint)
        assertEquals(listOf(listOf(0xFF112233.toInt(), press), listOf(0xFF112233.toInt())), fills(button))
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

    @Test
    fun aSelectedFillAloneLeavesTheButtonsOffAsEver() {
        styled("<style select=\"toolbar:selected\" fill=\"#ff112233\"/>", "<style select=\"toolbar:pressed\" overlay=\"#30000000\"/>")
        val button = ToolButton(ctx, R.drawable.ic_clipboard, ThemeManager.activeTheme)
        assertFalse(button.background is StateListDrawable)
        button.setOn(true)
        // on: a circle (no radius) of the fill, the overlay over it pressed
        assertEquals(listOf(listOf(0xFF112233.toInt(), 0x30000000), listOf(0xFF112233.toInt())), fills(button))
        assertTrue(states(button).last() is ToolButton.Circle)
    }

    @Test
    fun noRadiusIsACircleInTheMiddle() {
        val c = ToolButton.Circle(0)
        c.setBounds(0, 0, 100, 40)
        assertEquals(Rect(30, 0, 70, 40), c.bounds)
    }

    @Test
    fun anUnderlineGoesToEachLinesEndNotPastAWrap() {
        val paint = TextPaint().apply { textSize = 20f }
        // "aaaa bbbb" wrapped after "aaaa ": the span ending at the wrap draws nothing on the next line
        val ltr = StaticLayout.Builder.obtain("aaaa bbbb", 0, 9, paint, paint.measureText("aaaa ").toInt() + 1).build()
        assertEquals(2, ltr.lineCount)
        val first = PreeditUi.PreeditTextView.segments(ltr, 0, 5)
        assertEquals(listOf(0), first.map { it.line })
        // across the wrap: to the first line's right end, then from the second's start
        val across = PreeditUi.PreeditTextView.segments(ltr, 2, 7)
        assertEquals(listOf(0, 1), across.map { it.line })
        // the space it wrapped at underlined too, which the line's right (its ink's) leaves out
        assertEquals(paint.measureText("aaaa "), across[0].right, 0.5f)
        assertTrue(across[0].right > ltr.getLineRight(0) + 1)
        assertEquals(ltr.getLineLeft(1), across[1].left, 0.5f)
        // right to left: the first line goes on to its left end
        val hebrew = "\u05d0\u05d0\u05d0\u05d0 \u05d1\u05d1\u05d1\u05d1"
        val rtl = StaticLayout.Builder.obtain(hebrew, 0, 9, paint, paint.measureText(hebrew.take(5)).toInt() + 1).build()
        assertEquals(2, rtl.lineCount)
        val back = PreeditUi.PreeditTextView.segments(rtl, 2, 7)
        assertEquals(rtl.getPrimaryHorizontal(0) - paint.measureText(hebrew.take(5)), back[0].left, 0.5f)
        assertEquals(rtl.getPrimaryHorizontal(2), back[0].right, 0.5f)
    }

    // each state's layers' colours: pressed first, then the rest
    private fun fills(button: ToolButton): List<List<Int>> = states(button).map { d ->
        val layers = if (d is LayerDrawable) (0 until d.numberOfLayers).map { d.getDrawable(it) } else listOf(d)
        layers.map { (it as GradientDrawable).color!!.defaultColor }
    }

    private fun states(button: ToolButton): List<Drawable> =
        (button.background as StateListDrawable).let { s -> (0 until s.stateCount).map { s.getStateDrawable(it)!! } }

    // "ni hao", underlined as fcitx asks, its cursor in the middle
    private fun panel(): HostEvent.InputPanel {
        val empty = FormattedText(emptyArray(), IntArray(0), -1)
        return HostEvent.InputPanel(FormattedText(arrayOf("ni hao"), intArrayOf(TextFormatFlag.Underline.flag), 2), empty, empty)
    }

    private fun text(ui: PreeditUi): TextView = (ui.root as android.view.ViewGroup).getChildAt(0) as TextView
}
