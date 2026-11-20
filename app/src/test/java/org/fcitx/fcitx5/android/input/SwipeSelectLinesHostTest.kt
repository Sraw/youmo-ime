/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.text.InputType
import android.view.KeyEvent
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.SpaceCursor
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 滑选 across lines in a multi-line field through a real [KotlinHost]; the sideways steps are in [KeyShortcutsHostTest]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "zh-rCN-w393dp-h851dp-port-440dpi")
class SwipeSelectLinesHostTest {

    @get:Rule
    val prefs = PrefsRule()

    @get:Rule(order = 1)
    val host = HostKeyboardRule("swipe-select-lines")
    private val h get() = host.h
    private val fcitx get() = host.fcitx

    private fun start() {
        prefs.set(AppPrefs.getInstance().internal.keySwipes, SwipeSlots.Default.with("full/q", up = true, SwipeSlot.Command(KeyCommand.SwipeSelect)).serialize())
        host.up()
    }

    private fun swipeSelectHeld(k: ShownKey, vararg dxs: Float): KeyboardHarness.Finger {
        val finger = h.finger(k, h.at(k))
        for (i in 1..6) finger.move(h.at(k, dy = -10f * i, ms = 16))
        dxs.forEach { finger.move(h.at(k, dx = it, dy = -60f, ms = 16)) }
        return finger
    }

    private fun selected() = "${h.field.selectionStart}..${h.field.selectionEnd}"

    // the editor's own later selection must not be taken for a late line and set the other way round
    @Test
    fun swipeSelectLineNeverReportedIsNoLongerWaitedForAfterTheTouch() {
        start()
        fun field(): KeyboardHarness = h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) {
            it.setText("one\ntwo\nthree")
            it.setSelection(11)
            it.setOnKeyListener { _, code, _ -> code == KeyEvent.KEYCODE_DPAD_UP }
        }
        fun editorsOwnKept() {
            // from a selection: set from the cursor, the harness's field would order 11..3 itself and leave nothing to check
            h.field.setSelection(10, 11)
            h.field.setSelection(11, 3)
            assertEquals("set the other way round", "11..3", selected())
            h.idle()
            h.after(100)
            assertEquals("the editor's own selection kept", "11..3", selected())
        }
        // each row ends with its lines forgotten, so nothing carries into the next
        fun ended(cancelled: Boolean) = {
            field()
            host.synced()
            val q = h.key("q")
            val finger = swipeSelectHeld(q, -11f)
            host.until("a character left") { selected() == "10..11" }
            finger.move(h.at(q, dx = -11f, dy = -90f, ms = 16))
            h.after(400)
            if (cancelled) {
                // a second finger cancels the touch; the wait is out before the lift, which would end it again
                h.tap(h.key("x"))
                h.idle()
                h.after(400)
                editorsOwnKept()
                finger.up(h.at(q, dx = -11f, dy = -90f))
            } else {
                finger.up(h.at(q, dx = -11f, dy = -90f))
                host.synced()
                h.after(400)
                assertEquals("the touch over, in order", "10..11", selected())
                editorsOwnKept()
            }
        }
        assertRows(
            "lifted" to ended(cancelled = false),
            "cancelled by another key" to ended(cancelled = true),
        )
    }

    // guards against a line there moving nothing (the editor has no line to go to)
    @Test
    fun swipeSelectLineAtTheFieldsFirstOrLastLineGoesToItsEdge() {
        start()
        fun edge(at: Int, dy: Float, moved: String, lifted: String) = {
            h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) {
                it.setText("one\ntwo")
                it.setSelection(at)
            }
            host.synced()
            val q = h.key("q")
            val finger = swipeSelectHeld(q, -11f)
            host.until("a character left") { selected() == "${at - 1}..$at" }
            finger.move(h.at(q, dx = -11f, dy = dy, ms = 16))
            host.until("to the edge, the anchor kept") { selected() == moved }
            finger.up(h.at(q, dx = -11f, dy = dy))
            host.synced()
            assertEquals("lifted, in order", lifted, selected())
        }
        assertRows(
            "up at the first line: to the field's start" to edge(at = 2, dy = -90f, moved = "2..0", lifted = "0..2"),
            "down at the last line: to the field's end" to edge(at = 5, dy = -30f, moved = "5..7", lifted = "5..7"),
        )
    }

    // shift+Up/Down moves the free end a line on screen, the anchor kept; later steps go on from the editor's report; single-line takes no line
    @Test
    @Uat("KB-85")
    fun swipeSelectMovesItsFreeEndALineUpAndDown() {
        start()
        val keys = mutableListOf<String>()
        val names = mapOf(KeyEvent.KEYCODE_SHIFT_LEFT to "SHIFT_LEFT", KeyEvent.KEYCODE_DPAD_UP to "DPAD_UP", KeyEvent.KEYCODE_DPAD_DOWN to "DPAD_DOWN")
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) {
            it.setText("one\ntwo\nthree")
            it.setSelection(11)
            it.setOnKeyListener { _, code, e ->
                keys += "${if (e.action == KeyEvent.ACTION_DOWN) "v" else "^"}${names[code] ?: code}${if (e.isShiftPressed) "+shift" else ""}"
                false
            }
        }
        host.synced()
        val q = h.key("q")
        val finger = swipeSelectHeld(q, -11f)
        host.until("a character left") { selected() == "10..11" }
        finger.move(h.at(q, dx = -11f, dy = -90f, ms = 16))
        host.until("a line up, the anchor kept") { selected() == "11..6" }
        assertEquals(
            "shift held around the line key",
            listOf("vSHIFT_LEFT+shift", "vDPAD_UP+shift", "^DPAD_UP+shift", "^SHIFT_LEFT"),
            keys,
        )
        finger.move(h.at(q, dx = -21f, dy = -90f, ms = 16))
        host.until("sideways from the reported end") { selected() == "5..11" }
        finger.move(h.at(q, dx = -21f, dy = -60f, ms = 16))
        host.until("back down, the anchor kept: the editor's, the free end first") { selected() == "11..9" }
        finger.up(h.at(q, dx = -21f, dy = -60f))
        host.synced()
        assertEquals("lifted, in order; nothing typed", "9..11" to "one\ntwo\nthree", selected() to h.field.text.toString())

        h.start(prefill = { it.setText("hello"); it.setSelection(5) })
        host.synced()
        val again = swipeSelectHeld(q, -11f)
        host.until("a character left") { selected() == "4..5" }
        again.move(h.at(q, dx = -11f, dy = -150f, ms = 16))
        again.up(h.at(q, dx = -11f, dy = -150f))
        host.synced()
        assertEquals("a single-line field: no line", "4..5", selected())
    }


    // nothing reported: 300 ms on, the steps go on from where the end was
    @Test
    fun swipeSelectLineIgnoredGivesTheStepsBackAfterTheWait() {
        start()
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) {
            it.setText("one\ntwo\nthree")
            it.setSelection(11)
            it.setOnKeyListener { _, code, _ -> code == KeyEvent.KEYCODE_DPAD_UP }
        }
        host.synced()
        val q = h.key("q")
        val finger = swipeSelectHeld(q, -11f)
        host.until("a character left") { selected() == "10..11" }
        finger.move(h.at(q, dx = -11f, dy = -90f, ms = 16))
        finger.move(h.at(q, dx = -21f, dy = -90f, ms = 16))
        h.idle()
        assertEquals("the step waits for the line, the selection set from the anchor", "11..10", selected())
        h.after(400)
        host.until("then taken from where the end was") { selected() == "9..11" }
        finger.up(h.at(q, dx = -21f, dy = -90f))
    }

    // lines past the anchor stay selected at the lift (set the other way round it would collapse); two lines in one
    // sample: the second goes on from the first's free end, not undoing it
    @Test
    fun swipeSelectLinesAfterAStepStaySelectedAtTheLift() {
        fun lines(at: Int, step: String, dy: Float, lines: String, lifted: String) = {
            host.down()
            start()
            h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) {
                it.setText("one\ntwo\nthree")
                it.setSelection(at)
            }
            host.synced()
            val q = h.key("q")
            val finger = swipeSelectHeld(q, -11f)
            host.until("a character left") { selected() == step }
            finger.move(h.at(q, dx = -11f, dy = dy, ms = 16))
            host.until("the lines, the anchor kept") { selected() == lines }
            finger.up(h.at(q, dx = -11f, dy = dy))
            host.synced()
            h.after(400)
            assertEquals("lifted, in order", lifted, selected())
        }
        assertRows(
            "a line over the anchor" to lines(at = 6, step = "5..6", dy = -30f, lines = "6..9", lifted = "6..9"),
            "two lines in one sample" to lines(at = 11, step = "10..11", dy = -120f, lines = "11..2", lifted = "2..11"),
        )
    }

    // guards against the step taken after the line, from where the line had not yet landed
    @Test
    fun swipeSelectStepAndLineDownInOneSampleGoOnFromTheLine() {
        start()
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) {
            it.setText("one\ntwo\nthree")
            it.setSelection(1)
        }
        host.synced()
        val q = h.key("q")
        val finger = swipeSelectHeld(q, 11f)
        host.until("a character right") { selected() == "1..2" }
        val board = KeyboardHarness.all(h.keyboard).filterIsInstance<BaseKeyboard>().first { h.shown(it) }
        board.keyActionListener!!.onKeyAction(KeyAction.SwipeSelectAction(1, listOf(SpaceCursor.Line.Down)), KeyActionListener.Source.Keyboard)
        host.until("a step right, then a line down") { selected() == "1..7" }
        h.idle()
        h.after(400)
        board.keyActionListener!!.onKeyAction(KeyAction.SwipeSelectAction(-1), KeyActionListener.Source.Keyboard)
        host.until("sideways from where the line left the free end") { selected() == "1..6" }
        finger.up(h.at(q, dx = 11f, dy = -60f))
    }
}
