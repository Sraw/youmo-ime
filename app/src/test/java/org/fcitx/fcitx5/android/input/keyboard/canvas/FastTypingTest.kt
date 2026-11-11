/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fast typing (060-fast-typing): fingers that overlap, the next one down before the last lifts,
 * each type their own key, on the 14 keys and the 26.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class FastTypingTest {

    @get:Rule
    val prefs = PrefsRule()

    private var made: KeyboardHarness? = null

    @After
    fun close() {
        made?.close()
    }

    private fun harness(keyboard: ImKeyboard) =
        KeyboardHarness(keyboards = ImKeyboards.Chosen(pinyin = keyboard)).also { made = it }.start().also {
            it.idle()
            it.api.calls.clear()
        }

    private fun KeyboardHarness.sent() = api.calls.filter { it.startsWith("sendKey") && "up=false" in it }

    /** Fingers on the window, each by its id where it is now; every event carries all those down. */
    private class Fingers(private val h: KeyboardHarness) {
        private val start = SystemClock.uptimeMillis()
        private val down = linkedMapOf<Int, Pair<Float, Float>>()

        private fun send(action: Int, id: Int) {
            val ids = down.keys.toList()
            val props = Array(ids.size) { i -> MotionEvent.PointerProperties().apply { this.id = ids[i]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(ids.size) { i -> MotionEvent.PointerCoords().apply { x = down.getValue(ids[i]).first; y = down.getValue(ids[i]).second } }
            val index = ids.indexOf(id)
            val masked = when {
                action == MotionEvent.ACTION_MOVE -> action
                ids.size == 1 -> action
                action == MotionEvent.ACTION_DOWN -> MotionEvent.ACTION_POINTER_DOWN
                else -> MotionEvent.ACTION_POINTER_UP
            }
            val a = if (masked == MotionEvent.ACTION_POINTER_DOWN || masked == MotionEvent.ACTION_POINTER_UP) masked or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else masked
            val e = MotionEvent.obtain(start, SystemClock.uptimeMillis(), a, ids.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            ((h.inputView.parent as? ViewGroup) ?: h.inputView).dispatchTouchEvent(e)
            e.recycle()
        }

        fun down(id: Int, at: Pair<Float, Float>) {
            down[id] = at
            send(MotionEvent.ACTION_DOWN, id)
        }

        fun move(id: Int, at: Pair<Float, Float>) {
            down[id] = at
            send(MotionEvent.ACTION_MOVE, id)
        }

        fun up(id: Int) {
            send(MotionEvent.ACTION_UP, id)
            down.remove(id)
        }
    }

    // across the key: 0.25 its left half, 0.75 its right
    private fun KeyboardHarness.spot(k: ShownKey, across: Float = 0.5f) = box(k).let { it.left + it.width() * across to it.exactCenterY() }

    @Test
    fun fourteenKeysOverlappingTapsOnTwoKeysTypeBoth() {
        val h = harness(ImKeyboard.KEYS14)
        val f = Fingers(h)
        f.down(0, h.spot(h.keyNamed("r2c3")))
        h.after(40)
        f.down(1, h.spot(h.keyNamed("r2c5")))
        h.after(30)
        f.up(0)
        h.after(30)
        f.up(1)
        h.idle()
        assertEquals(h.sent().toString(), 2, h.sent().size)
    }

    // "dui"'s u then i: one key, two fingers, the second down before the first lifts
    @Test
    @Uat("PY-119")
    fun fourteenKeysOverlappingTapsOnOneKeyTypeBoth() {
        val h = harness(ImKeyboard.KEYS14)
        val ui = h.keyNamed("r1c4")
        val f = Fingers(h)
        f.down(0, h.spot(ui, 0.25f))
        h.after(40)
        f.down(1, h.spot(ui, 0.75f))
        h.after(30)
        f.up(0)
        h.after(30)
        f.up(1)
        h.idle()
        assertEquals(h.sent().toString(), 2, h.sent().size)
        // u where the first finger was, i where the second: each its own half
        assertNotEquals(h.sent().toString(), h.sent()[0], h.sent()[1])
    }

    // the first finger drifts a little while the second is down on the same key
    @Test
    @Uat("PY-119")
    fun fourteenKeysOverlappingOneKeyWithDriftTypeBoth() {
        val h = harness(ImKeyboard.KEYS14)
        val ui = h.keyNamed("r1c4")
        val (x0, y0) = h.spot(ui, 0.25f)
        val f = Fingers(h)
        f.down(0, x0 to y0)
        h.after(30)
        f.down(1, h.spot(ui, 0.75f))
        for (i in 1..6) {
            f.move(0, x0 + i to y0 - i)
            h.after(5)
        }
        h.after(10)
        f.up(0)
        h.after(30)
        f.up(1)
        h.idle()
        assertEquals(h.sent().toString(), 2, h.sent().size)
        assertNotEquals(h.sent().toString(), h.sent()[0], h.sent()[1])
    }

    // the same key tapped twice quickly, one finger at a time: two letters, no double tap
    @Test
    fun fourteenKeysOneKeyTappedTwiceQuicklyTypesTwo() {
        val h = harness(ImKeyboard.KEYS14)
        val ui = h.keyNamed("r1c4")
        val f = Fingers(h)
        f.down(0, h.spot(ui, 0.25f))
        h.after(30)
        f.up(0)
        h.after(40)
        f.down(1, h.spot(ui, 0.75f))
        h.after(30)
        f.up(1)
        h.idle()
        assertEquals(h.sent().toString(), 2, h.sent().size)
        assertNotEquals(h.sent().toString(), h.sent()[0], h.sent()[1])
    }

    // three fingers rolling over the 26 keys: a down, b down, a up, c down, b up, c up
    @Test
    fun fullKeysRollingOverThreeKeysTypesThree() {
        val h = harness(ImKeyboard.FULL)
        val f = Fingers(h)
        f.down(0, h.spot(h.keyNamed("d")))
        h.after(30)
        f.down(1, h.spot(h.keyNamed("u")))
        h.after(20)
        f.up(0)
        f.down(0, h.spot(h.keyNamed("i")))
        h.after(20)
        f.up(1)
        h.after(20)
        f.up(0)
        h.idle()
        assertEquals(h.sent().toString(), 3, h.sent().size)
    }
}
