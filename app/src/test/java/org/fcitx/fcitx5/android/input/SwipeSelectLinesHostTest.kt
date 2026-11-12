/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.text.InputType
import android.view.KeyEvent
import java.io.File
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.core.ScriptedEngine
import org.fcitx.fcitx5.android.core.keyboardOn
import org.fcitx.fcitx5.android.core.startedHost
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.SpaceCursor
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.fcitx.fcitx5.android.until as waitUntil

/**
 * 滑选 across lines in a multi-line field, through a real [KotlinHost] on a scripted engine: lines up
 * and down, at the field's first and last line, sent several in a sample, ignored or never reported,
 * and what is felt (its sideways steps in [KeyShortcutsHostTest]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "zh-rCN-w393dp-h851dp-port-440dpi")
class SwipeSelectLinesHostTest {

    @get:Rule
    val prefs = PrefsRule()

    private lateinit var home: File
    private lateinit var fcitx: KotlinHost
    private lateinit var h: KeyboardHarness

    private fun start() {
        prefs.set(AppPrefs.getInstance().internal.keySwipes, SwipeSlots.Default.with("full/q", up = true, SwipeSlot.Command(KeyCommand.SwipeSelect)).serialize())
        home = File(appContext.filesDir, "swipe-select-lines").apply { deleteRecursively() }
        fcitx = startedHost(ScriptedEngine(home))
        h = keyboardOn(fcitx)
    }

    @After
    fun tearDown() {
        if (::h.isInitialized) h.close()
        if (::fcitx.isInitialized) fcitx.stop()
        if (::home.isInitialized) home.deleteRecursively()
    }

    private fun until(what: String, done: () -> Boolean) = waitUntil(what, { "field [${h.field.text}]" }, step = { h.idle() }, done = done)

    private fun synced() {
        var done = false
        h.service.postFcitxJob { done = true }
        until("the host's queue done") { done }
        h.idle()
    }

    // 滑选 on q: up past the threshold, then sideways to each of [dxs], the finger left down
    private fun swipeSelectHeld(k: ShownKey, vararg dxs: Float): KeyboardHarness.Finger {
        val finger = h.finger(k, h.at(k))
        for (i in 1..6) finger.move(h.at(k, dy = -10f * i, ms = 16))
        dxs.forEach { finger.move(h.at(k, dx = it, dy = -60f, ms = 16)) }
        return finger
    }

    private fun selected() = "${h.field.selectionStart}..${h.field.selectionEnd}"

    // the touch over while the editor ignoring shift+arrow never reported its line: a while on, what the
    // editor reports is its own; guards against the editor's own selection taken for a line late to land
    // and set the other way round
    @Test
    fun swipeSelectLineNeverReportedIsNoLongerWaitedForAfterTheTouch() {
        start()
        fun field(): KeyboardHarness = h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) {
            it.setText("one\ntwo\nthree")
            it.setSelection(11)
            it.setOnKeyListener { _, code, _ -> code == KeyEvent.KEYCODE_DPAD_UP }
        }
        fun editorsOwnKept() {
            // from a selection, as the lifted row has: set from the cursor, the harness's field puts 11..3 in order
            // by itself (no IME involved), which would leave nothing to check
            h.field.setSelection(10, 11)
            h.field.setSelection(11, 3)
            assertEquals("set the other way round", "11..3", selected())
            h.idle()
            h.after(100)
            assertEquals("the editor's own selection kept", "11..3", selected())
        }
        // the rows share the host and its listener: each ends with its lines forgotten (the wait after the touch
        // waited out), so nothing of 滑选 carries into the next
        fun ended(cancelled: Boolean) = {
            field()
            synced()
            val q = h.key("q")
            val finger = swipeSelectHeld(q, -11f)
            until("a character left") { selected() == "10..11" }
            finger.move(h.at(q, dx = -11f, dy = -90f, ms = 16))
            h.after(400)
            if (cancelled) {
                // another key acting (a second finger) in the same field: the touch cancelled, the finger still down;
                // the wait after it waited out before the lift, which would end it again
                h.tap(h.key("x"))
                h.idle()
                h.after(400)
                editorsOwnKept()
                finger.up(h.at(q, dx = -11f, dy = -90f))
            } else {
                finger.up(h.at(q, dx = -11f, dy = -90f))
                synced()
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

    // at the field's first or last line the line goes to its start or end, from the anchor; guards
    // against a line there moving nothing (the editor has no line to go to)
    @Test
    fun swipeSelectLineAtTheFieldsFirstOrLastLineGoesToItsEdge() {
        start()
        fun edge(at: Int, dy: Float, moved: String, lifted: String) = {
            h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) {
                it.setText("one\ntwo")
                it.setSelection(at)
            }
            synced()
            val q = h.key("q")
            val finger = swipeSelectHeld(q, -11f)
            until("a character left") { selected() == "${at - 1}..$at" }
            finger.move(h.at(q, dx = -11f, dy = dy, ms = 16))
            until("to the edge, the anchor kept") { selected() == moved }
            finger.up(h.at(q, dx = -11f, dy = dy))
            synced()
            assertEquals("lifted, in order", lifted, selected())
        }
        assertRows(
            "up at the first line: to the field's start" to edge(at = 2, dy = -90f, moved = "2..0", lifted = "0..2"),
            "down at the last line: to the field's end" to edge(at = 5, dy = -30f, moved = "5..7", lifted = "5..7"),
        )
    }

    // KB-85: after a sideways step, up and down have the editor move the free end a line on screen as the
    // space's slide moves the cursor (shift with Up/Down, about 28 dp a line), the anchor kept; the steps
    // after it go on from where the editor reports it; a single-line field takes no line
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
        synced()
        val q = h.key("q")
        val finger = swipeSelectHeld(q, -11f)
        until("a character left") { selected() == "10..11" }
        finger.move(h.at(q, dx = -11f, dy = -90f, ms = 16))
        until("a line up, the anchor kept") { selected() == "11..6" }
        assertEquals(
            "shift held around the line key",
            listOf("vSHIFT_LEFT+shift", "vDPAD_UP+shift", "^DPAD_UP+shift", "^SHIFT_LEFT"),
            keys,
        )
        finger.move(h.at(q, dx = -21f, dy = -90f, ms = 16))
        until("sideways from the reported end") { selected() == "5..11" }
        finger.move(h.at(q, dx = -21f, dy = -60f, ms = 16))
        until("back down, the anchor kept: the editor's, the free end first") { selected() == "11..9" }
        finger.up(h.at(q, dx = -21f, dy = -60f))
        synced()
        assertEquals("lifted, in order; nothing typed", "9..11" to "one\ntwo\nthree", selected() to h.field.text.toString())

        h.start(prefill = { it.setText("hello"); it.setSelection(5) })
        synced()
        val again = swipeSelectHeld(q, -11f)
        until("a character left") { selected() == "4..5" }
        again.move(h.at(q, dx = -11f, dy = -150f, ms = 16))
        again.up(h.at(q, dx = -11f, dy = -150f))
        synced()
        assertEquals("a single-line field: no line", "4..5", selected())
    }

    // a line taking the free end from the left of the anchor past it: lifted, the editor's selection,
    // in order already, stays (set the other way round it would collapse to a cursor)
    @Test
    fun swipeSelectLineOverTheAnchorStaysSelectedAtTheLift() {
        start()
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) {
            it.setText("one\ntwo\nthree")
            it.setSelection(6)
        }
        synced()
        val q = h.key("q")
        val finger = swipeSelectHeld(q, -11f)
        until("a character left") { selected() == "5..6" }
        finger.move(h.at(q, dx = -11f, dy = -30f, ms = 16))
        until("a line down, past the anchor") { selected() == "6..9" }
        finger.up(h.at(q, dx = -11f, dy = -30f))
        synced()
        h.after(400)
        assertEquals("still selected", "6..9", selected())
    }

    // an editor ignoring shift+arrow reports nothing: 300 ms on, the steps moved meanwhile go on from where the end was
    @Test
    fun swipeSelectLineIgnoredGivesTheStepsBackAfterTheWait() {
        start()
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) {
            it.setText("one\ntwo\nthree")
            it.setSelection(11)
            it.setOnKeyListener { _, code, _ -> code == KeyEvent.KEYCODE_DPAD_UP }
        }
        synced()
        val q = h.key("q")
        val finger = swipeSelectHeld(q, -11f)
        until("a character left") { selected() == "10..11" }
        finger.move(h.at(q, dx = -11f, dy = -90f, ms = 16))
        finger.move(h.at(q, dx = -21f, dy = -90f, ms = 16))
        h.idle()
        assertEquals("the step waits for the line, the selection set from the anchor", "11..10", selected())
        h.after(400)
        until("then taken from where the end was") { selected() == "9..11" }
        finger.up(h.at(q, dx = -21f, dy = -90f))
    }

    // two lines in one sample: the second goes on from the first's free end, the anchor kept (not set again from where it was);
    // guards against the second line undoing the first, the selection set again from where it began
    @Test
    fun swipeSelectTwoLinesInOneSampleKeepEachOther() {
        start()
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) {
            it.setText("one\ntwo\nthree")
            it.setSelection(11)
        }
        synced()
        val q = h.key("q")
        val finger = swipeSelectHeld(q, -11f)
        until("a character left") { selected() == "10..11" }
        finger.move(h.at(q, dx = -11f, dy = -120f, ms = 16))
        until("two lines up, the anchor kept") { selected() == "11..2" }
        finger.up(h.at(q, dx = -11f, dy = -120f))
        synced()
        // both lines in the editor's one report: the second waited for till given up on
        h.after(400)
        assertEquals("lifted, in order", "2..11", selected())
    }

    // felt once a sample that moved it, sideways and lines together; a sample moving nothing not felt; guards against
    // a buzz for each line and step of one sample, and one for a sample at the field's edge moving nothing
    @Test
    fun swipeSelectIsFeltOnceASample() {
        start()
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) {
            it.setText("one\ntwo\nthree")
            it.setSelection(11)
        }
        synced()
        val q = h.key("q")
        val finger = swipeSelectHeld(q, -11f)
        until("a character left") { selected() == "10..11" }
        val board = KeyboardHarness.all(h.keyboard).filterIsInstance<BaseKeyboard>().first { h.shown(it) }
        var felt = 0
        board.keyActionListener!!.onKeyAction(
            KeyAction.SwipeSelectAction(-1, listOf(SpaceCursor.Line.Up, SpaceCursor.Line.Up)) { felt++ },
            KeyActionListener.Source.Keyboard,
        )
        assertEquals("a step and two lines: once", 1, felt)
        until("moved") { selected() == "11..1" }
        // the editor's report delivered
        h.after(400)
        board.keyActionListener!!.onKeyAction(KeyAction.SwipeSelectAction(-2) { felt++ }, KeyActionListener.Source.Keyboard)
        until("sideways from where the lines left the free end") { selected() == "0..11" }
        assertEquals("a sample moving it: felt again", 2, felt)
        board.keyActionListener!!.onKeyAction(KeyAction.SwipeSelectAction(0, listOf(SpaceCursor.Line.Up)) { felt++ }, KeyActionListener.Source.Keyboard)
        h.idle()
        assertEquals("at the field's start already: nothing moved, not felt", 2, felt)
        finger.up(h.at(q, dx = -11f, dy = -60f))
    }

    // a step right and a line down in one sample: the step from the anchor, the line from there, the next step from where it
    // landed; guards against the step taken after the line, from where the line had not yet landed
    @Test
    fun swipeSelectStepAndLineDownInOneSampleGoOnFromTheLine() {
        start()
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) {
            it.setText("one\ntwo\nthree")
            it.setSelection(1)
        }
        synced()
        val q = h.key("q")
        val finger = swipeSelectHeld(q, 11f)
        until("a character right") { selected() == "1..2" }
        val board = KeyboardHarness.all(h.keyboard).filterIsInstance<BaseKeyboard>().first { h.shown(it) }
        board.keyActionListener!!.onKeyAction(KeyAction.SwipeSelectAction(1, listOf(SpaceCursor.Line.Down)), KeyActionListener.Source.Keyboard)
        until("a step right, then a line down") { selected() == "1..7" }
        h.idle()
        h.after(400)
        board.keyActionListener!!.onKeyAction(KeyAction.SwipeSelectAction(-1), KeyActionListener.Source.Keyboard)
        until("sideways from where the line left the free end") { selected() == "1..6" }
        finger.up(h.at(q, dx = 11f, dy = -60f))
    }
}
