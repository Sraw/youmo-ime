/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKeyCell
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayersChoice
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

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

    private fun key() = KeyCaps.key(ctx, "W", "2", marked = false, dimmed = false)

    // the symbol's own TextView: in a column under the letter, or in the frame's top right
    private fun under(key: FrameLayout) = key.getChildAt(0) is LinearLayout

    private fun corner(key: FrameLayout, gravity: Int): Boolean =
        (0 until key.childCount).map { key.getChildAt(it) }.any {
            it is TextView && it.text == "2" && (it.layoutParams as FrameLayout.LayoutParams).gravity == gravity
        }

    @Test
    @Uat("ST-51")
    fun atBottomTheSymbolIsUnderTheLetterButInItsCornerWhenCompact() {
        prefs.set(AppPrefs.getInstance().keyboard.punctuationPosition, KeyMarkPosition.Bottom)
        val standard = key()
        assertTrue(under(standard))
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 20)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 34)
        // the shortcuts top left (the default): top right
        assertTrue(corner(key(), Gravity.TOP or Gravity.END))
        // the shortcuts top right: bottom right
        prefs.set(AppPrefs.getInstance().keyboard.shortcutMarkPosition, KeyMarkPosition.TopRight)
        assertTrue(corner(key(), Gravity.BOTTOM or Gravity.END))
        // 滑动 moves nothing
        prefs.set(AppPrefs.getInstance().keyboard.swipeLayers, SwipeLayersChoice.SymbolUpShortcutDown)
        assertTrue(corner(key(), Gravity.BOTTOM or Gravity.END))
        // 无 is none whatever the height
        prefs.set(AppPrefs.getInstance().keyboard.punctuationPosition, KeyMarkPosition.None)
        assertEquals(1, key().childCount)
    }

    @Test
    fun theOtherCornersAsChosen() {
        val at = mapOf(
            KeyMarkPosition.TopRight to (Gravity.TOP or Gravity.END),
            KeyMarkPosition.BottomRight to (Gravity.BOTTOM or Gravity.END),
            KeyMarkPosition.TopLeft to (Gravity.TOP or Gravity.START),
            KeyMarkPosition.BottomLeft to (Gravity.BOTTOM or Gravity.START),
        )
        prefs.set(AppPrefs.getInstance().keyboard.shortcutMarkPosition, KeyMarkPosition.Bottom)
        for ((position, gravity) in at) {
            prefs.set(AppPrefs.getInstance().keyboard.punctuationPosition, position)
            assertTrue("$position", corner(key(), gravity))
        }
    }

    // the engine's cap (060-render-p2 S6 review): dimmed (its long press off), its words faded alone, the face and
    // the symbol as they are, never a disabled key's fading; edited, outlined along the face drawn in the keyboard
    // theme's accent
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theEnginesCapFadesItsWordsAloneAndOutlinesItsFace() {
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, true)
        fun cap(marked: Boolean, dimmed: Boolean): SceneKeyCell =
            KeyCaps.key(ctx, "W", "2", marked = marked, dimmed = dimmed).let { k ->
                k.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
                k.layout(0, 0, W, H)
                (0 until k.childCount).map(k::getChildAt).filterIsInstance<SceneKeyCell>().single()
            }
        val plain = cap(marked = false, dimmed = false).drawn()
        val dim = cap(marked = false, dimmed = true).drawn()
        assertEquals(plain.size, dim.size)
        for ((a, b) in plain.zip(dim)) {
            if (a is Op.Text && a.text == "W") {
                assertEquals(a.copy(color = Argb(0)), (b as Op.Text).copy(color = Argb(0)))
                // 0.4 of its alpha, the colour itself kept
                assertEquals((a.color.alpha * 0.4).roundToInt(), b.color.alpha)
                assertEquals(a.color.value and 0xFFFFFF, b.color.value and 0xFFFFFF)
            } else {
                assertEquals("the face and the symbol as they are", a, b)
            }
        }
        val edited = cap(marked = true, dimmed = false)
        val drawn = edited.drawn()
        assertEquals(plain, drawn.dropLast(1))
        val line = drawn.last() as Op.Stroke
        assertEquals(edited.keyNode!!.shape, line.shape)
        assertEquals(Argb(ThemeManager.activeTheme.accentKeyBackgroundColor), line.color)
    }

    companion object {
        private const val W = 150
        private const val H = 160
    }
}
