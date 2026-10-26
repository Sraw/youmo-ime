/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.MotionEvent
import android.view.View
import androidx.core.view.allViews
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.pinyin.g14Code
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A 14-key tap sends where across the key the finger came down (design k14-tap-position §1.4,
 * review M1, M2): driven by real touches, as a finger's, never by performClick, which no touch
 * makes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class GroupedKeyboardTapTest {

    private val context = RuntimeEnvironment.getApplication()
    private val sent = ArrayList<KeyAction>()

    // the keys' timers run in the view tree's lifecycle
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    private val keyboard = GroupedKeyboard(context, ThemePreset.PixelDark).apply {
        setViewTreeLifecycleOwner(Owner())
        keyActionListener = KeyActionListener { action, _ -> sent += action }
        measure(exactly(1080), exactly(800))
        layout(0, 0, 1080, 800)
    }

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private fun key(face: String) = keyboard.allViews.filterIsInstance<AltTextKeyView>().first { (it.def as KeyDef.Appearance.AltText).displayText == face }

    private fun code(c: Char) = KeyAction.SymAction(KeySym(UNICODE + c.code), KeyStates.Virtual)

    private fun tap(c: Char, at: Float) = KeyAction.SymAction(KeySym(UNICODE + Keyboard.tapKey(c, at)), KeyStates.Virtual)

    // a touch on [view]: down at [x], then each move by (dx, dy) from there, then up where the last left it
    private fun touch(view: View, x: Float, vararg moves: Pair<Float, Float>) {
        val y = view.height / 2f
        var time = 1_000L
        fun send(action: Int, ex: Float, ey: Float) {
            val event = MotionEvent.obtain(1_000L, time, action, ex, ey, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
            time += 10
        }
        send(MotionEvent.ACTION_DOWN, x, y)
        var last = x to y
        for ((dx, dy) in moves) {
            last = x + dx to y + dy
            send(MotionEvent.ACTION_MOVE, last.first, last.second)
        }
        send(MotionEvent.ACTION_UP, last.first, last.second)
    }

    @Test
    fun aTapSendsItsCodeWithWhereTheFingerCameDown() {
        val bn = key("BN")
        assertTrue(bn.width > 0)
        touch(bn, bn.width * 0.75f)
        assertEquals(listOf<KeyAction>(tap(g14Code('b'), 0.75f)), sent)
        sent.clear()
        touch(bn, bn.width * 0.25f)
        assertEquals(listOf<KeyAction>(tap(g14Code('b'), 0.25f)), sent)
    }

    @Test
    fun aFingerThatMovedALittleStillTapsWithItsPlace() {
        // a real finger moves a few pixels between down and up: no swipe, the place kept
        val bn = key("BN")
        touch(bn, bn.width * 0.75f, 2f to 1f)
        assertEquals(listOf<KeyAction>(tap(g14Code('b'), 0.75f)), sent)
    }

    @Test
    fun aSwipeTheOtherWayIsATapWithNoPlace() {
        // up, past the threshold, where the corner is down: SymbolSwipe's Tap, the code alone
        val bn = key("BN")
        touch(bn, bn.width * 0.75f, 0f to -bn.height.toFloat() / 2 + 1, 0f to -bn.height.toFloat() / 2 + 1)
        assertTrue(sent.toString(), sent.isNotEmpty() && sent.all { it == code(g14Code('b')) })
    }

    @Test
    fun aKeyOfOneLetterAndAClickNoTouchMadeSendTheCode() {
        val m = key("M")
        touch(m, m.width * 0.9f)
        assertEquals(listOf<KeyAction>(code(g14Code('m'))), sent)
        sent.clear()
        // TalkBack's double tap clicks with no touch
        key("BN").performClick()
        assertEquals(listOf<KeyAction>(code(g14Code('b'))), sent)
    }

    // a CANCEL clears the place too, but DOWN already has, so no test can see that: it is a safeguard
    @Test
    fun thePlaceIsTheClicksAloneClearedAfterIt() {
        val bn = key("BN")
        var seen = Float.NaN
        bn.setOnClickListener { seen = (it as CustomGestureView).clickAt }
        touch(bn, bn.width * 0.75f)
        assertEquals(0.75f, seen, 1e-3f)
        assertTrue(bn.clickAt.isNaN())
    }
}
