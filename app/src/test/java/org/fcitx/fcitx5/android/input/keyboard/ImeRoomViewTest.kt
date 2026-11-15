/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.NavbarBackground
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * 061-landscape-height §5.2 in the input view: the keys worked out in the room the window gives, less what the view
 * keeps under them for the navigation bar, once laid out (not the room predicted), and again as the bar's insets come.
 * A phone on its side at 很高 (4 × 59 dp asked), the input method's bar 48 dp as under gestures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w851dp-h393dp-land-440dpi")
class ImeRoomViewTest {

    @get:Rule
    val prefs = PrefsRule()

    private fun dp(v: Int) = (v * RuntimeEnvironment.getApplication().resources.displayMetrics.density).roundToInt()

    private class Shown(val app: Int, val keys: Int)

    // the bar under the window as the system tells it, the view laid out again (twice: the keys worked out after it)
    private fun KeyboardHarness.shownUnder(nav: Int): Shown {
        val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, nav)).build()
        ViewCompat.dispatchApplyWindowInsets(inputView, insets)
        relayout()
        relayout()
        return Shown(box(keyboard).top - box(inputView).top, box(board).height())
    }

    private fun under(background: NavbarBackground, keyboards: ImKeyboards.Chosen? = null): Shown {
        prefs.set(ThemeManager.prefs.navbarBackground, background)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 60)
        prefs.set(AppPrefs.getInstance().internal.zhuyinTraditionalTold, true)
        return KeyboardHarness(keyboards = keyboards).use { h ->
            h.start()
            h.relayout()
            h.relayout()
            h.shownUnder(dp(48))
        }
    }

    @Test
    @Uat("LS-47", "LS-48")
    fun theAppKeeps120dpUnderEachBackground() {
        listOf(NavbarBackground.Full, NavbarBackground.ColorOnly, NavbarBackground.None).forEach {
            val shown = under(it)
            assertTrue("$it: app ${shown.app} px", shown.app >= dp(KeyboardHeightCap.MIN_APP_DP) - 2)
            assertTrue("$it: keys ${shown.keys} px", shown.keys >= dp(4 * KeyboardHeightCap.MIN_ROW_DP) - 2)
        }
    }

    @Test
    fun withoutTheBarKeptTheKeysHaveItsRoom() {
        // a window not fitted above the bar (edge to edge): 全部 keeps the bar's 48 dp under the keys, 无 gives it them,
        // the floor binding both ways (the room laid out, not the 70% of a screen of 0.6.0)
        val full = under(NavbarBackground.Full)
        val none = under(NavbarBackground.None)
        assertEquals(dp(KeyboardHeightCap.MIN_APP_DP).toFloat(), full.app.toFloat(), 2f)
        assertEquals(dp(KeyboardHeightCap.MIN_APP_DP).toFloat(), none.app.toFloat(), 2f)
        assertEquals(dp(48).toFloat(), (none.keys - full.keys).toFloat(), 2f)
    }

    @Test
    fun theFirstFrameLeavesTheAppNoLessThanTheLast() {
        // the first frame is worked out in the room predicted (ImeRoom), before the view is laid out or the bar's insets
        // come: it errs to the app's side, so the field never shows hidden and then comes out (no short-then-tall)
        prefs.set(ThemeManager.prefs.navbarBackground, NavbarBackground.Full)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 60)
        KeyboardHarness().use { h ->
            h.start()
            val dm = RuntimeEnvironment.getApplication().resources.displayMetrics
            h.inputView.measure(
                View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY),
            )
            h.inputView.layout(0, 0, dm.widthPixels, dm.heightPixels)
            val first = h.box(h.keyboard).top - h.box(h.inputView).top
            h.idle()
            val last = h.shownUnder(dp(48))
            assertTrue("first $first px, last ${last.app} px", first >= last.app - 2)
        }
    }

    @Test
    @Uat("LS-47")
    fun theBarsInsetsComingWorkTheKeysOutAgain() {
        // laid out before the system tells of the bar (0), then told (48 dp): the keys give the bar its room, the app
        // keeps its 120 dp; a dp or less is no new room (DockedRoomTest)
        prefs.set(ThemeManager.prefs.navbarBackground, NavbarBackground.Full)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 60)
        KeyboardHarness().use { h ->
            h.start()
            val none = h.shownUnder(0)
            val bar = h.shownUnder(dp(48))
            assertEquals(dp(KeyboardHeightCap.MIN_APP_DP).toFloat(), none.app.toFloat(), 2f)
            assertEquals(dp(KeyboardHeightCap.MIN_APP_DP).toFloat(), bar.app.toFloat(), 2f)
            assertEquals(dp(48).toFloat(), (none.keys - bar.keys).toFloat(), 2f)
            val again = h.shownUnder(dp(48) + 1)
            assertEquals(bar.keys, again.keys)
        }
    }

    @Test
    @Uat("LS-50")
    @Config(qualifiers = "w780dp-h380dp-land-440dpi")
    fun aSmallPhonesZhuyinKeepsItsFiveRows() {
        // 332 dp of room: the letters' four rows give the app its 120 dp, 注音's five keep 36 dp each and take from it
        val pinyin = under(NavbarBackground.Full)
        val zhuyin = under(NavbarBackground.Full, ImKeyboards.Chosen(pinyin = ImKeyboard.ZHUYIN_FULL))
        assertEquals(dp(KeyboardHeightCap.MIN_APP_DP).toFloat(), pinyin.app.toFloat(), 2f)
        assertTrue("${pinyin.keys} px", pinyin.keys > dp(4 * KeyboardHeightCap.MIN_ROW_DP))
        assertEquals(dp(5 * KeyboardHeightCap.MIN_ROW_DP).toFloat(), zhuyin.keys.toFloat(), 2f)
        assertTrue("${zhuyin.app} px", zhuyin.app < pinyin.app)
    }
}
