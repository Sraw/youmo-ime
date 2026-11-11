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
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.ShownKey
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertEquals
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

    // the keysym a 14-key tap of [letter]'s key at [at] across sends ([Keyboard.tapKey])
    private fun tapSym(letter: Char, at: Float) = UNICODE + Keyboard.tapKey(NineKeyLetters.G14.key(letter), at)

    private fun symOf(call: String) = Regex("sym=(\\d+)").find(call)!!.groupValues[1].toInt()

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
        // GH's middle then L (one letter, so its plain code, no place), once each: a finger's key typed twice, or the other's lost, fails
        assertEquals(h.sent().toString(), listOf(tapSym('g', 0.5f), UNICODE + NineKeyLetters.G14.key('l').code), h.sent().map(::symOf))
    }

    // "dui"'s u then i on UI, a finger each: u where the first came down (the left half), then i
    // (the right), in that order, however the two fingers' times fall
    @Test
    @Uat("PY-119")
    fun fourteenKeysTwoFingersOnOneKeyTypeEachItsHalf() {
        fun typed(fingers: Fingers.(KeyboardHarness, Pair<Float, Float>, Pair<Float, Float>) -> Unit): List<Int> {
            made?.close()
            val h = harness(ImKeyboard.KEYS14)
            val ui = h.keyNamed("r1c4")
            Fingers(h).fingers(h, h.spot(ui, 0.25f), h.spot(ui, 0.75f))
            h.idle()
            return h.sent().map(::symOf)
        }
        val ui = listOf(tapSym('u', 0.25f), tapSym('i', 0.75f))
        assertRows(
            "the second finger down before the first lifts" to {
                assertEquals(ui, typed { h, left, right ->
                    down(0, left)
                    h.after(40)
                    down(1, right)
                    h.after(30)
                    up(0)
                    h.after(30)
                    up(1)
                })
            },
            "the first finger drifts a little while the second is down: still a tap, not a swipe" to {
                assertEquals(ui, typed { h, left, right ->
                    val (x0, y0) = left
                    down(0, left)
                    h.after(30)
                    down(1, right)
                    for (i in 1..6) {
                        move(0, x0 + i to y0 - i)
                        h.after(5)
                    }
                    h.after(10)
                    up(0)
                    h.after(30)
                    up(1)
                })
            },
            "one finger at a time, quickly: two letters, not a double tap" to {
                assertEquals(ui, typed { h, left, right ->
                    down(0, left)
                    h.after(30)
                    up(0)
                    h.after(40)
                    down(1, right)
                    h.after(30)
                    up(1)
                })
            },
        )
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
        // d, u, i once each in the order the fingers came down: a finger's key typed twice fails
        assertEquals(h.sent().toString(), listOf("d", "u", "i"), h.sent().map { Regex("key=(.)").find(it)!!.groupValues[1] })
    }

    // ten keys 40 ms apart, a finger each, the next down before the last lifts, while the host takes
    // 30–80 ms a key: every key reaches it, in the order typed
    private fun slowHost(keyboard: ImKeyboard, ids: List<String>): List<String> {
        val h = harness(keyboard)
        h.api.keyDelay = { listOf(30L, 80L, 55L, 45L, 70L)[it % 5] }
        val f = Fingers(h)
        ids.forEachIndexed { n, id ->
            f.down(n % 2, h.spot(h.keyNamed(id), if (n % 3 == 0) 0.25f else 0.75f))
            h.after(20)
            if (n > 0) f.up((n - 1) % 2)
            h.after(20)
        }
        f.up((ids.size - 1) % 2)
        h.after(1500)
        h.idle()
        return h.sent()
    }

    @Test
    fun fourteenKeysTenFastKeysOnABusyHostAllArriveInOrder() {
        // UI twice running, and other keys; a letter of each key's for its tap ([tapSym])
        val ids = listOf("r2c2", "r1c4", "r1c4", "r2c3", "r1c4", "r1c4", "r2c1", "r1c4", "r1c4", "r2c3")
        val letter = mapOf("r2c1" to 'a', "r2c2" to 'd', "r2c3" to 'g', "r1c4" to 'u')
        val sent = slowHost(ImKeyboard.KEYS14, ids)
        // each key where slowHost tapped it (its left half every third, else its right), in the order typed
        val typed = ids.mapIndexed { n, id -> tapSym(letter.getValue(id), if (n % 3 == 0) 0.25f else 0.75f) }
        assertEquals(sent.toString(), typed, sent.map(::symOf))
    }

    @Test
    fun fullKeysTenFastKeysOnABusyHostAllArriveInOrder() {
        val ids = "duihuisuig".map { it.toString() }
        val sent = slowHost(ImKeyboard.FULL, ids)
        assertEquals(sent.toString(), ids, sent.map { Regex("key=(.)").find(it)!!.groupValues[1] })
    }
}
