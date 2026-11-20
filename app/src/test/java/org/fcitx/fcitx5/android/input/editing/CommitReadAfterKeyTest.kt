/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * fcitx hands back the up of every key it took and the service replays it; an up changes no text, so the next
 * commit is not read again. A down handed back (a digit the app inserts itself) changes the field: the next reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi-keysexposed-qwerty")
class CommitReadAfterKeyTest {

    private var harness: KeyboardHarness? = null

    @After
    fun close() {
        harness?.close()
    }

    private fun event(action: Int): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(now, now, action, KeyEvent.KEYCODE_SPACE, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
    }

    private val KeyboardHarness.commits: CommitCheck get() = service.commitCheck

    /** Down and up sent to fcitx; the timestamps they carry, down's first. */
    private fun KeyboardHarness.space(): List<Int> {
        service.onKeyDown(KeyEvent.KEYCODE_SPACE, event(KeyEvent.ACTION_DOWN))
        service.onKeyUp(KeyEvent.KEYCODE_SPACE, event(KeyEvent.ACTION_UP))
        advance(20)
        assertEquals("the space's down and up sent to fcitx", 2, api.keyTimestamps.size)
        return api.keyTimestamps.toList()
    }

    private fun KeyboardHarness.handedBack(up: Boolean, timestamp: Int) =
        emit(HostEvent.Key(FcitxKeyMapping.FcitxKey_space, 0, ' '.code, up, timestamp))

    /** The reads 吗's commit makes, before any wait for the field runs out. */
    private fun KeyboardHarness.readsCommitting(): Int {
        beforeReads.clear()
        emit(HostEvent.Commit("吗", -1))
        return beforeReads.count { it == CommitCheck.READ }
    }

    private fun landedThenSpace(): Pair<KeyboardHarness, List<Int>> {
        val h = KeyboardHarness().also { harness = it }
        h.start()
        h.emit(HostEvent.Commit("你好", -1))
        h.advance(CommitCheck.WAIT_MS + 50)
        return h to h.space()
    }

    @Test
    fun aKeysUpHandedBackLeavesTheTextBeforeTheCursorKnown() {
        val (h, sent) = landedThenSpace()
        h.handedBack(up = true, sent[1])
        assertEquals(0, h.readsCommitting())
        h.advance(CommitCheck.WAIT_MS + 50)
        assertEquals("你好吗", h.field.text.toString())
    }

    @Test
    fun aKeysDownHandedBackIsTypedByTheAppAndTheNextCommitReadsThoughTheFieldHasNotReportedIt() {
        val (h, sent) = landedThenSpace()
        h.selectionLags = true
        h.handedBack(up = false, sent[0])
        // read before the commit and right after it: the text before the cursor was not known
        assertEquals(2, h.readsCommitting())
        assertEquals("你好 吗", h.field.text.toString())
    }

    @Test
    fun theUpOfTheSpaceThatChoseAWordHandedBackBeforeTheFieldReportsLeavesTheCommitJudged() {
        val h = KeyboardHarness().also { harness = it }
        h.start()
        h.writesDropped = true
        val sent = h.space()
        // the down chose 你好; its up comes back with the commit, before any report could
        h.emit(HostEvent.Commit("你好", -1))
        h.handedBack(up = true, sent[1])
        h.advance(CommitCheck.WAIT_MS + 50)
        assertEquals("你好", h.commits.kept)
    }
}
