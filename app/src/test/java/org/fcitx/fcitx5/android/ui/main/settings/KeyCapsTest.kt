/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayersChoice
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKeyCell
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The settings' keys put a key's symbol where the keyboard does: where 标点位置 says; 底部 under the
 * letter, but at 紧凑 top right (bottom right where 快捷键图标位置 is top right).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class KeyCapsTest {

    @get:Rule
    val prefs = PrefsRule()

    private val ctx get() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_FcitxAppTheme)

    private enum class At { Under, TopRight, BottomRight, TopLeft, BottomLeft, None }

    // the key as the page lays it out, the engine's cell in it
    private fun key(): FrameLayout = KeyCaps.key(ctx, "W", "2", marked = false, dimmed = false).apply {
        measure(View.MeasureSpec.makeMeasureSpec(SIZE, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(SIZE, View.MeasureSpec.EXACTLY))
        layout(0, 0, SIZE, SIZE)
    }

    // where the engine drew the symbol, from the letter: under it (in its column), or in a corner of the key
    private fun at(key: FrameLayout): At {
        val cell = (0 until key.childCount).map(key::getChildAt).filterIsInstance<SceneKeyCell>().single()
        val texts = cell.keyNode!!.ops(false).filterIsInstance<Op.Text>()
        val letter = texts.single { it.text == "W" }
        val symbol = texts.singleOrNull { it.text == "2" } ?: return At.None
        // a text drawn from its left: its middle, its width about its size a character
        fun middle(t: Op.Text) = t.x + t.size * t.scaleX * 0.3 * t.text.length
        val below = symbol.baseline > letter.baseline
        val side = middle(symbol) - SIZE / 2.0
        return when {
            below && kotlin.math.abs(side) < SIZE / 10.0 -> At.Under
            below && side > 0 -> At.BottomRight
            below -> At.BottomLeft
            side > 0 -> At.TopRight
            else -> At.TopLeft
        }
    }

    @Test
    @Uat("ST-51")
    fun atBottomTheSymbolIsUnderTheLetterButInItsCornerWhenCompact() {
        prefs.set(AppPrefs.getInstance().keyboard.punctuationPosition, KeyMarkPosition.Bottom)
        assertEquals(At.Under, at(key()))
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 20)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 34)
        // the shortcuts top left (the default): top right
        assertEquals(At.TopRight, at(key()))
        // the shortcuts top right: bottom right
        prefs.set(AppPrefs.getInstance().keyboard.shortcutMarkPosition, KeyMarkPosition.TopRight)
        assertEquals(At.BottomRight, at(key()))
        // 滑动 moves nothing
        prefs.set(AppPrefs.getInstance().keyboard.swipeLayers, SwipeLayersChoice.SymbolUpShortcutDown)
        assertEquals(At.BottomRight, at(key()))
        // 无 is none whatever the height
        prefs.set(AppPrefs.getInstance().keyboard.punctuationPosition, KeyMarkPosition.None)
        assertEquals(At.None, at(key()))
    }

    @Test
    fun theOtherCornersAsChosen() {
        val corners = mapOf(
            KeyMarkPosition.TopRight to At.TopRight,
            KeyMarkPosition.BottomRight to At.BottomRight,
            KeyMarkPosition.TopLeft to At.TopLeft,
            KeyMarkPosition.BottomLeft to At.BottomLeft,
        )
        prefs.set(AppPrefs.getInstance().keyboard.shortcutMarkPosition, KeyMarkPosition.Bottom)
        for ((position, corner) in corners) {
            prefs.set(AppPrefs.getInstance().keyboard.punctuationPosition, position)
            assertEquals("$position", corner, at(key()))
        }
    }

    private companion object {
        // a key cap's size on the page, about 56dp at 440dpi
        const val SIZE = 154
    }
}
