/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.app.Activity
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayersChoice
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKeyCell
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.utils.styledColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import kotlin.math.roundToInt

/**
 * The settings' keys put a key's symbol where the keyboard does: where 标点位置 says; 底部 under the
 * letter, but at 紧凑 top right (bottom right where 快捷键图标位置 is top right).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
        // the ops at the keyboard key's size the cap stands for (SceneKeyCell.keyboardKeyHeight)
        val w = cell.scene!!.width
        val side = middle(symbol) - w / 2.0
        return when {
            below && kotlin.math.abs(side) < w / 10.0 -> At.Under
            below && side > 0 -> At.BottomRight
            below -> At.BottomLeft
            side > 0 -> At.TopRight
            else -> At.TopLeft
        }
    }

    // a tap as a finger makes it, down and up: the frame's click, though the engine's cell under it is a gesture view
    @Test
    fun aTapOnTheKeyIsItsClick() {
        val k = key()
        // in a window: a view not in one queues its click for an attach that never comes
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(FrameLayout(ctx).apply { addView(k, SIZE, SIZE) })
        ShadowLooper.idleMainLooper()
        var clicks = 0
        k.setOnClickListener { clicks++ }
        val c = SIZE / 2f
        k.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, c, c, 0))
        k.dispatchTouchEvent(MotionEvent.obtain(0, 50, MotionEvent.ACTION_UP, c, c, 0))
        ShadowLooper.idleMainLooper()
        assertEquals(1, clicks)
    }

    @Test
    @Uat("ST-51")
    fun atBottomTheSymbolIsUnderTheLetterButInItsCornerWhenCompact() {
        prefs.set(AppPrefs.getInstance().keyboard.swipeUpPosition, KeyMarkPosition.Bottom)
        assertEquals(At.Under, at(key()))
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 20)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 34)
        // the shortcuts top left (the default): top right
        assertEquals(At.TopRight, at(key()))
        // the shortcuts top right: bottom right
        prefs.set(AppPrefs.getInstance().keyboard.swipeDownPosition, KeyMarkPosition.TopRight)
        assertEquals(At.BottomRight, at(key()))
        // 滑动 moves nothing
        prefs.setLayers(SwipeLayersChoice.SymbolUpShortcutDown)
        assertEquals(At.BottomRight, at(key()))
        // 无 is none whatever the height
        prefs.set(AppPrefs.getInstance().keyboard.swipeUpPosition, KeyMarkPosition.None)
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
        prefs.set(AppPrefs.getInstance().keyboard.swipeDownPosition, KeyMarkPosition.Bottom)
        for ((position, corner) in corners) {
            prefs.set(AppPrefs.getInstance().keyboard.swipeUpPosition, position)
            assertEquals("$position", corner, at(key()))
        }
    }

    // the engine's cap (060-render-p2 S6 review): dimmed (its long press off), its words faded alone, the face and
    // the symbol as they are, never a disabled key's fading; edited, outlined along the face drawn in the keyboard
    // theme's accent
    @Test
    @Uat("ST-22")
    fun theEnginesCapFadesItsWordsAloneAndOutlinesItsFace() {
        fun cap(marked: Boolean, dimmed: Boolean, selected: Boolean = false): SceneKeyCell =
            KeyCaps.key(ctx, "W", "2", marked = marked, dimmed = dimmed, selected = selected).let { k ->
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
        // the item acted on (selected) outlined in the settings' colour, alone or over the marked outline (R20 F6504)
        val primary = Argb(ctx.styledColor(android.R.attr.colorPrimary))
        for (marked in listOf(false, true)) {
            val ops = cap(marked = marked, dimmed = false, selected = true).drawn()
            assertEquals(plain, ops.dropLast(1))
            assertEquals("marked=$marked", primary, (ops.last() as Op.Stroke).color)
        }
    }

    // a cap is the keyboard's key made at its size and drawn shrunk to the cap (060-pending-decisions, the
    // shortcut page's marks where the keyboard puts them): its ops the key's at the keyboard's height
    @Test
    fun theEnginesCapIsTheKeyboardsKeyShrunkToIt() {
        val k = KeyCaps.keyboardKeyHeight(ctx)
        assertTrue("$k", k > H)
        fun laid(v: View, w: Int, h: Int) = v.apply {
            measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            layout(0, 0, w, h)
        }
        val cap = laid(KeyCaps.key(ctx, "W", "2", marked = false, dimmed = false), W, H).let { f ->
            (0 until (f as FrameLayout).childCount).map(f::getChildAt).filterIsInstance<SceneKeyCell>().single()
        }
        assertEquals(k, cap.keyboardKeyHeight)
        val key = laid(SceneKeyCell(ctx, ThemeManager.activeTheme).apply { attrs = cap.attrs; marks = cap.marks }, (W.toDouble() * k / H).roundToInt(), k) as SceneKeyCell
        assertEquals(key.drawn(), cap.drawn())
    }

    private companion object {
        private const val W = 150
        private const val H = 160
        // a key cap's size on the page, about 56dp at 440dpi
        const val SIZE = 154
    }
}
