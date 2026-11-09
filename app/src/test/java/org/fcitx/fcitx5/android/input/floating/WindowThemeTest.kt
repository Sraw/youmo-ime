/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View
import android.widget.ImageView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.NavbarBackground
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.NavigationBarManager
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.SplitKeyboardMode
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneThemes
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneWindow
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
import java.io.File
import kotlin.math.roundToInt

/**
 * 060-render-p3 T7a in the app: floating, one-handed and split, the window round the keys is the
 * theme's (`frame`, `handle`, `side-button`, `split-gap`, `navbar`), and a theme that sets none of it,
 * a theme 3.0's, has the app's look of before (the defaults' pixels: ThemeBaselineCompareTest).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = WindowThemeTest.PORTRAIT)
class WindowThemeTest {

    @get:Rule
    val prefs = PrefsRule()

    private val realSource = SceneThemes.source

    @After
    fun tearDown() {
        SceneThemes.source = realSource
        RuntimeEnvironment.setQualifiers(PORTRAIT)
    }

    // a theme 3.0's file and every T7a property set in it
    private fun everything() {
        SceneThemes.source = { look, name, dark -> LookTheme.xml(look, name, dark).replace("</theme>", "$STYLES\n</theme>") }
    }

    private val theme get() = ThemeManager.prefs

    private fun dp(h: KeyboardHarness, v: Int) = (v * h.service.resources.displayMetrics.density).toInt()

    private fun handle(h: KeyboardHarness): FloatingHandle = KeyboardHarness.find<FloatingHandle>(h.inputView) ?: error("no handle")

    private fun card(h: KeyboardHarness): View =
        KeyboardHarness.all(h.inputView.rootView).first { it.outlineProvider?.javaClass?.name?.contains("FloatingCorners") == true }

    private fun radius(v: View) = Outline().also { v.outlineProvider.getOutline(v, it) }.radius

    private fun sideButtons(h: KeyboardHarness): List<ImageView> = listOf(R.string.split_dock_move_left, R.string.split_dock_move_right, R.string.one_handed_exit)
        .flatMap { d -> KeyboardHarness.all(h.inputView).filter { it is ImageView && it.contentDescription == h.service.getString(d) }.toList() }
        .map { it as ImageView }

    private fun draw(v: View): Bitmap = Bitmap.createBitmap(maxOf(1, v.width), maxOf(1, v.height), Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }

    // the picture of a mode for the reviewer's eye (060-render-p3 T7a's in place of an emulator's)
    private fun keep(h: KeyboardHarness, name: String) {
        val dir = File("build/t7a-window").also { it.mkdirs() }
        File(dir, "$name.png").outputStream().use { draw(h.inputView).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun started(h: KeyboardHarness): KeyboardHarness = h.apply {
        start()
        after(1000)
        relayout()
    }

    // the navigation bar [NAV] px tall under the keyboard, as the system tells it
    private fun navigationBar(h: KeyboardHarness) {
        val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAV)).build()
        ViewCompat.dispatchApplyWindowInsets(h.inputView, insets)
        h.relayout()
    }

    @Suppress("DEPRECATION")
    private fun barColor(h: KeyboardHarness) = h.service.window.window!!.navigationBarColor

    // the bar's buttons drawn dark (a light bar's appearance)
    private fun darkButtons(h: KeyboardHarness) = h.service.window.window!!.let {
        WindowCompat.getInsetsController(it, it.decorView).isAppearanceLightNavigationBars
    }

    @Test
    fun floatingTheFrameAndTheHandleAreTheThemes() {
        everything()
        prefs.set(theme.floatingKeyboard, true)
        KeyboardHarness().use { h ->
            started(h)
            val card = card(h)
            assertEquals(dp(h, 20).toFloat(), radius(card), 0f)
            assertEquals(dp(h, 12).toFloat(), card.elevation, 0f)
            assertEquals(GREEN, card.outlineSpotShadowColor)
            assertEquals(GREEN, card.outlineAmbientShadowColor)
            // the border round the inside, over the keys: its colour at the left edge's middle
            assertEquals(RED, draw(card).getPixel(0, card.height / 2))
            val handle = handle(h)
            assertEquals(BLUE, (handle.background as ColorDrawable).color)
            val grip = (0 until handle.childCount).map(handle::getChildAt).first { it.background is GradientDrawable }
            assertEquals(dp(h, 60) to dp(h, 8), grip.layoutParams.width to grip.layoutParams.height)
            assertEquals(YELLOW, (grip.background as GradientDrawable).color!!.defaultColor)
            val dock = h.described(h.service.getString(R.string.floating_keyboard_dock)) as ImageView
            assertEquals(PorterDuffColorFilter(CYAN, PorterDuff.Mode.SRC_IN), dock.colorFilter)
            // floating, the app is under the bar, not the keyboard: no colour of the theme's there
            navigationBar(h)
            assertTrue(strips(h).isEmpty())
            keep(h, "floating")
            // the hardware keyboard's candidates alone: the bar the theme's, the floating setting on or not
            NavigationBarManager().evaluate(h.service.window.window!!, false)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) assertEquals(BROWN, barColor(h))
            assertFalse("light buttons on the dark brown", darkButtons(h))
        }
    }

    @Test
    fun oneHandedTheSideButtonsAndTheNavigationBarAreTheThemes() {
        everything()
        prefs.set(theme.oneHanded, OneHanded.Left)
        prefs.set(theme.alwaysShowHandle, true)
        KeyboardHarness().use { h ->
            started(h)
            val shown = sideButtons(h).filter { h.shown(it) }
            assertEquals("the arrow across and the whole width again", 2, shown.size)
            for (b in shown) sideButton(h, b)
            // 全部 (the default), at 29 as at 35: the keyboard drawn behind the bar, a strip in the bar's room
            // under the keys of the theme's colour, `@one-handed`'s; the docked handle's of it too
            navigationBar(h)
            assertEquals(listOf(NAV), strips(h, GREY))
            assertEquals(NAVY, (handle(h).background as ColorDrawable).color)
            keep(h, "one_handed_left")
        }
    }

    @Test
    fun colourOnlyTheBarsColourAndItsButtonsTheThemes() {
        everything()
        prefs.set(theme.oneHanded, OneHanded.Left)
        prefs.set(theme.navbarBackground, NavbarBackground.ColorOnly)
        KeyboardHarness().use { h ->
            started(h)
            // 仅颜色: the keyboard not under the bar, no strip; the bar's own colour (below 35: from it the
            // window is drawn behind the bar whatever it is told), `@one-handed`'s mid grey, dark buttons on it
            navigationBar(h)
            assertTrue(strips(h, GREY).isEmpty())
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) assertEquals(GREY, barColor(h))
            assertTrue("dark buttons on the mid grey", darkButtons(h))
        }
    }

    @Test
    fun theBarsButtonsAreTheColourThatStandsOutMore() {
        // black on a mid grey stands out more than white, though its luminance is under a half (0.22)
        assertTrue(SceneWindow.darkButtonsOn(GREY))
        assertTrue(SceneWindow.darkButtonsOn(0xFFFFFFFF.toInt()))
        assertTrue(SceneWindow.darkButtonsOn(0x00FFFFFF))
        assertFalse(SceneWindow.darkButtonsOn(0xFF000000.toInt()))
        assertFalse(SceneWindow.darkButtonsOn(BROWN))
        assertFalse(SceneWindow.darkButtonsOn(0xFF595959.toInt()))
    }

    @Test
    fun splitTheSideButtonsAndTheGapAreTheThemes() {
        everything()
        prefs.set(theme.splitKeyboard, SplitKeyboardMode.Always)
        RuntimeEnvironment.setQualifiers(LANDSCAPE)
        KeyboardHarness().use { h ->
            started(h)
            // between the halves, at a letter's height: the gap's colour
            val t = h.key("t").bounds
            val y = h.key("y").bounds
            val at = IntArray(2).also(h.inputView::getLocationInWindow)
            val picture = draw(h.inputView)
            assertEquals(GAP, picture.getPixel((t.right + y.left) / 2 - at[0], t.centerY() - at[1]))
            keep(h, "split_land")
            // a board but the letters at a side: its buttons beside it
            h.tap(h.key("符"))
            h.after(1000)
            h.relayout()
            val shown = sideButtons(h).filter { h.shown(it) }
            assertEquals("the arrow across", 1, shown.size)
            sideButton(h, shown.single())
            keep(h, "split_land_symbols")
        }
    }

    @Test
    fun aThemeOfNoneIsTheAppsLookOfBefore() {
        prefs.set(theme.floatingKeyboard, true)
        prefs.set(theme.floatingHandle, FloatingHandleSide.Top)
        val t = TestThemes.PixelLight
        KeyboardHarness(t).use { h ->
            started(h)
            // FloatingController's corners of 0.5.2: the keys' radius and their least margin, truncated px
            val keys = ThemeManager.shownLook(t).keys
            assertEquals(dp(h, FloatingKeyboard.cornerDp(keys.radiusDp, keys.gap.h, keys.gap.v, keys.blocks)).toFloat(), radius(card(h)), 0f)
            assertEquals(dp(h, 6).toFloat(), card(h).elevation, 0f)
            assertEquals(null, card(h).foreground)
            // FloatingHandle's: a shade of the bar, the grip 36 × 4 of the function keys' text
            val handle = handle(h)
            assertEquals(oldHandleColor(t.barColor, t.keyTextColor), (handle.background as ColorDrawable).color)
            val grip = (0 until handle.childCount).map(handle::getChildAt).first { it.background is GradientDrawable }
            assertEquals(dp(h, 36) to dp(h, 4), grip.layoutParams.width to grip.layoutParams.height)
            assertEquals(t.altKeyTextColor, (grip.background as GradientDrawable).color!!.defaultColor)
        }
        // at the bottom, the keyboard's own under it
        prefs.set(theme.floatingHandle, FloatingHandleSide.Bottom)
        KeyboardHarness(t).use { h ->
            started(h)
            assertEquals(null, handle(h).background)
        }
        prefs.set(theme.floatingKeyboard, false)
        prefs.set(theme.oneHanded, OneHanded.Left)
        KeyboardHarness(t).use { h ->
            started(h)
            for (b in sideButtons(h).filter { h.shown(it) }) {
                assertEquals(PorterDuffColorFilter(t.altKeyTextColor, PorterDuff.Mode.SRC_IN), b.colorFilter)
                assertEquals("the borderless ripple alone", 0, (b.background as RippleDrawable).numberOfLayers)
            }
            navigationBar(h)
            assertTrue("no strip of a colour of its own", KeyboardHarness.all(h.inputView).none { it.isShown && it.height == NAV })
        }
    }

    // a look that made no theme (SceneThemes refused it, its keys not drawn): the window the look's of before
    @Test
    fun aLookThatMadeNoThemeIsTheAppsLookOfBefore() {
        SceneThemes.source = { _, _, _ -> "<theme/>" }
        prefs.set(theme.floatingKeyboard, true)
        prefs.set(theme.floatingHandle, FloatingHandleSide.Top)
        val t = TestThemes.PixelLight
        KeyboardHarness(t).use { h ->
            started(h)
            val keys = ThemeManager.shownLook(t).keys
            assertEquals(dp(h, FloatingKeyboard.cornerDp(keys.radiusDp, keys.gap.h, keys.gap.v, keys.blocks)).toFloat(), radius(card(h)), 0f)
            assertEquals(dp(h, 6).toFloat(), card(h).elevation, 0f)
            assertEquals(oldHandleColor(t.barColor, t.keyTextColor), (handle(h).background as ColorDrawable).color)
        }
    }

    // a side button as STYLES has it: a rectangle 6 dp round of its fill, its icon's colour
    private fun sideButton(h: KeyboardHarness, b: ImageView) {
        assertEquals(PorterDuffColorFilter(ICON, PorterDuff.Mode.SRC_IN), b.colorFilter)
        val shape = (b.background as RippleDrawable).getDrawable(0) as GradientDrawable
        assertEquals(MAGENTA, shape.color!!.defaultColor)
        assertEquals(GradientDrawable.RECTANGLE, shape.shape)
        assertEquals(dp(h, 6).toFloat(), shape.cornerRadius, 0f)
    }

    // the heights of the views shown, of some height, of [color] (STYLES' navigation bar's)
    private fun strips(h: KeyboardHarness, color: Int = BROWN): List<Int> = KeyboardHarness.all(h.inputView)
        .filter { it.isShown && it.height > 0 && (it.background as? ColorDrawable)?.color == color }.map { it.height }.toList()

    // FloatingKeyboard.handleColor of 0.5.2
    private fun oldHandleColor(base: Int, ink: Int): Int {
        fun mix(shift: Int): Int {
            val b = base ushr shift and 0xFF
            val i = ink ushr shift and 0xFF
            return (b + (i - b) * 0.1f).roundToInt() shl shift
        }
        return (base and 0xFF000000.toInt()) or mix(16) or mix(8) or mix(0)
    }

    companion object {
        const val PORTRAIT = "w393dp-h851dp-port-440dpi"
        private const val LANDSCAPE = "w851dp-h393dp-land-440dpi"

        private const val RED = 0xFFFF0000.toInt()
        private const val GREEN = 0xFF00FF00.toInt()
        private const val BLUE = 0xFF0000FF.toInt()
        private const val YELLOW = 0xFFFFFF00.toInt()
        private const val CYAN = 0xFF00FFFF.toInt()
        private const val MAGENTA = 0xFFFF00FF.toInt()
        private const val ICON = 0xFF112233.toInt()
        private const val GAP = 0xFF123456.toInt()
        private const val BROWN = 0xFF654321.toInt()
        private const val GREY = 0xFF808080.toInt()
        private const val NAVY = 0xFF000080.toInt()

        // the navigation bar's height, px
        private const val NAV = 48

        // every T7a property, each of a colour of its own
        private const val STYLES = """
  <style select="frame" radius="20dp" border="3dp #ff0000" shadow="0 0 12dp #00ff00"/>
  <style select="handle" fill="#0000ff" color="#ffff00" icon-color="#00ffff" width="60dp" height="8dp"/>
  <style select="side-button" fill="#ff00ff" icon-color="#112233" shape="rect" radius="6dp" overlay="#40000000"/>
  <style select="split-gap" fill="#123456"/>
  <style select="navbar" color="#654321"/>
  <style select="@one-handed navbar" color="#808080"/>
  <style select="@one-handed handle" fill="#000080"/>
  <style select="side-button:pressed" overlay="#50000000"/>"""
    }
}
