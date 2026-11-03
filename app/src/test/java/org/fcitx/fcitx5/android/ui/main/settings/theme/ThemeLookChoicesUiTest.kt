/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.theme.BoardBase
import org.fcitx.fcitx5.android.input.theme.Crop
import org.fcitx.fcitx5.android.input.theme.PressEffect
import org.fcitx.fcitx5.android.input.theme.Picture
import org.fcitx.fcitx5.android.input.theme.ThemeLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN")
class ThemeLookChoicesUiTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val look = ThemePreset.PixelLight.look

    private val picked = mutableListOf<ThemeLook>()

    private fun ui(l: ThemeLook) = ThemeLookChoicesUi(ctx, l) { picked += it }

    private fun ViewGroup.all(): List<View> = (0 until childCount).flatMap { i ->
        val c = getChildAt(i)
        listOf(c) + ((c as? ViewGroup)?.all() ?: emptyList())
    }

    private fun ThemeLookChoicesUi.button(text: Int) =
        root.all().filterIsInstance<RadioButton>().filter { it.text == ctx.getString(text) }

    private fun ThemeLookChoicesUi.label(text: Int) =
        root.all().filterIsInstance<TextView>().first { it !is RadioButton && it.text.startsWith(ctx.getString(text)) }

    @Test
    @Uat("ST-53")
    fun aStepPickedIsTheLookGivenAndTheChoiceShown() {
        val ui = ui(look)
        // 直角: of the corners
        ui.button(R.string.level_square).single().performClick()
        assertEquals(0, picked.last().keys.radiusDp)
        assertTrue(ui.button(R.string.level_square).single().isChecked)
        ui.button(R.string.theme_press_ripple).single().performClick()
        assertEquals(PressEffect.Ripple, picked.last().keys.press)
        // the corners still as picked: each choice from the look the last one gave
        assertEquals(0, picked.last().keys.radiusDp)
        ui.button(R.string.theme_keys_flat).single().performClick()
        assertFalse(picked.last().keys.blocks)
        assertEquals(3, picked.size)
    }

    @Test
    fun aValueOfNoStepShowsCustomTillOneIsPicked() {
        val ui = ui(look.copy(keys = look.keys.copy(radiusDp = 7)))
        // 自定义 is the corners' alone (the base, the keys, the corners, the border, the gap, the press)
        val custom = ui.button(R.string.theme_level_custom)
        assertEquals(listOf(false, false, true, false, false, false), custom.map { it.visibility == View.VISIBLE })
        assertTrue(custom[2].isChecked)
        assertFalse("shown, not pickable", custom[2].isEnabled)
        ui.button(R.string.level_round).single().performClick()
        assertEquals(10, picked.single().keys.radiusDp)
        assertEquals(View.GONE, ui.button(R.string.theme_level_custom)[2].visibility)
    }

    @Test
    fun theBaseAndTheBlurAreOnlyForAThemeOfAPicture() {
        val plain = ui(look)
        assertEquals(View.GONE, plain.label(R.string.theme_base).visibility)
        assertEquals(View.GONE, plain.label(R.string.theme_blur).visibility)

        val pictured = look.copy(picture = Picture("s.png", Crop("p.png", null, 0), null, 70, 0), base = BoardBase.Picture)
        val ui = ui(pictured)
        assertEquals(View.VISIBLE, ui.label(R.string.theme_base).visibility)
        ui.button(R.string.theme_base_color).single().performClick()
        assertEquals(BoardBase.Color, picked.last().base)
        val blur = ui.root.all().filterIsInstance<SeekBar>().single()
        // as a finger drags it
        shadowOf(blur).onSeekBarChangeListener.onProgressChanged(blur, 12, true)
        assertEquals(12, picked.last().picture!!.blurDp)
        assertEquals(BoardBase.Color, picked.last().base)
    }

    private fun shadowOf(bar: SeekBar) = org.robolectric.Shadows.shadowOf(bar)
}
