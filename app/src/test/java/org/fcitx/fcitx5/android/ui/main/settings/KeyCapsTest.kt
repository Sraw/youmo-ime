/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.view.ContextThemeWrapper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The settings' keys put a key's symbol where the keyboard does: 底部 under the letter, but top right at 紧凑. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class KeyCapsTest {

    @get:Rule
    val prefs = PrefsRule()

    private val ctx get() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_FcitxAppTheme)

    private fun key() = KeyCaps.key(ctx, "W", "2", marked = false, dimmed = false)

    // the symbol's own TextView: in a column under the letter, or in the frame's top right
    private fun under(key: FrameLayout) = key.getChildAt(0) is LinearLayout

    private fun topRight(key: FrameLayout): Boolean =
        (0 until key.childCount).map { key.getChildAt(it) }.any {
            it is TextView && it.text == "2" && (it.layoutParams as FrameLayout.LayoutParams).gravity == (Gravity.TOP or Gravity.END)
        }

    @Test
    @Uat("ST-51")
    fun atBottomTheSymbolIsUnderTheLetterButTopRightWhenCompact() {
        prefs.set(ThemeManager.prefs.punctuationPosition, PunctuationPosition.Bottom)
        val standard = key()
        assertTrue(under(standard))
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 20)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 34)
        val compact = key()
        assertTrue(topRight(compact))
        // 无 is none whatever the height
        prefs.set(ThemeManager.prefs.punctuationPosition, PunctuationPosition.None)
        assertEquals(1, key().childCount)
    }
}
