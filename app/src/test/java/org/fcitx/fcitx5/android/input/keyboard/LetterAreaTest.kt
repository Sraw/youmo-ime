/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Where the toolbar's 手写 writes (061-handwriting-toolbar §3.2, §9): the letters' keys' hit areas, the gaps
 * between them shared out as a tap's are, Shift with them on the 26 keys and 1 on the nine keys; ⌫ and the
 * nine keys' side columns not. A point is in the area exactly where the key a tap there would press is one of
 * the area's, whatever the gap or edge.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class LetterAreaTest {

    @get:Rule
    val prefs = PrefsRule()

    private var open: KeyboardHarness? = null

    @After
    fun close() {
        open?.close()
    }

    private class Board(val keyboard: BaseKeyboard, val canvas: KeyboardCanvasView, val area: LetterArea) {
        // in the area at ([x], [y]) of the canvas, as the keyboard asks it (its own pixels)
        fun at(x: Double, y: Double) = area.at(canvas, (x + canvas.left).toFloat(), (y + canvas.top).toFloat())
        fun key(id: String): KeyNode = canvas.keyAt(id) ?: error("no key $id")
        fun pressed(x: Double, y: Double) = canvas.keyAt(x.toFloat(), y.toFloat())
    }

    private inline fun <reified K : BaseKeyboard> board(keyboards: ImKeyboards.Chosen? = null): Board {
        prefs.set(AppPrefs.getInstance().internal.handwritingOpen, true)
        val h = KeyboardHarness(keyboards = keyboards).also { open = it }.start()
        val k = KeyboardHarness.find<K>(h.keyboard) ?: error("no ${K::class.simpleName}")
        val canvas = KeyboardHarness.all(k).filterIsInstance<KeyboardCanvasView>().first { it.scene != null }
        return Board(k, canvas, k.letterAreaForTest ?: error("not written on"))
    }

    // every point of the keyboard a few pixels apart: in the area just where the key pressed there is [inArea]
    private fun everywhere(b: Board, inArea: (KeyNode) -> Boolean) {
        val wrong = ArrayList<String>()
        var y = 0.5
        while (y < b.canvas.height) {
            var x = 0.5
            while (x < b.canvas.width) {
                val k = b.pressed(x, y)
                val want = k != null && inArea(k)
                if (b.at(x, y) != want) wrong += "($x, $y) on ${k?.id}: ${!want}"
                x += 3
            }
            y += 3
        }
        assertTrue(wrong.take(10).toString(), wrong.isEmpty())
    }

    private class Point(val why: String, val x: Double, val y: Double, val inArea: Boolean)

    @Test
    fun theTwentySixKeysLettersAndShiftNotBackspace() {
        val b = board<TextKeyboard>()
        val letters = ('a'..'z').map { it.toString() }.toSet()
        everywhere(b) { it.id in letters || it.role == "shift" }
        val q = b.key("q").bounds
        val w = b.key("w").bounds
        val a = b.key("a").bounds
        val l = b.key("l").bounds
        val z = b.key("z").bounds
        val shift = b.key("shift").bounds
        val backspace = b.key("backspace").bounds
        assertRows(
            listOf(
                Point("the gap between q and w", (q.right + w.left) / 2, q.centerY, true),
                Point("the gap below q (between the first two rows)", q.centerX, (q.bottom + a.top) / 2, true),
                Point("left of a, the row's edge", a.left / 2, a.centerY, true),
                Point("right of l, the row's edge", (l.right + b.canvas.width) / 2, l.centerY, true),
                Point("Shift", shift.centerX, shift.centerY, true),
                Point("the gap between Shift and z", (shift.right + z.left) / 2, z.centerY, true),
                Point("⌫", backspace.centerX, backspace.centerY, false),
            ),
            Point::why,
        ) { p ->
            assertEquals("pressed ${b.pressed(p.x, p.y)?.id}", p.inArea, b.at(p.x, p.y))
        }
    }

    @Test
    fun theNineKeysLettersAndOneNotTheSideColumns() {
        val b = board<T9Keyboard>(KeyboardHarness.NINE_KEYS)
        val keys = ('1'..'9').map { it.toString() }.toSet()
        everywhere(b) { it.id in keys }
        val one = b.key("1").bounds
        val three = b.key("3").bounds
        val backspace = b.key("backspace").bounds
        // the gap between the letters and the side column, each side's half going as a tap there does: [everywhere]'s. The
        // area at a point is the key a tap there presses asked (LetterArea.at), so an edge pixel adds no answer that key's
        // other pixels in [everywhere] do not give; the press's own answer as its row's expectation, the edges' rows were the
        // same check again (R26 F8361, F8485)
        assertRows(
            listOf(
                Point("the gap between 2 and 3", (b.key("2").bounds.right + three.left) / 2, one.centerY, true),
                Point("1", one.centerX, one.centerY, true),
                Point("⌫, the right column", backspace.centerX, backspace.centerY, false),
            ),
            Point::why,
        ) { p ->
            assertEquals("pressed ${b.pressed(p.x, p.y)?.id}", p.inArea, b.at(p.x, p.y))
        }
    }
}
