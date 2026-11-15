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
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The read before a commit (CommitCheck.before) on a hardware keyboard: fcitx hands back the up of
 * every key it took, the space that chose a word too, and the service replays it to the app. A key's
 * up changes no text, so the next commit at the cursor the last one left is not read and the commit
 * that space chose is still judged; a key's down handed back (a digit the app inserts itself) is a
 * change to the field, and the next commit reads.
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

    private val KeyboardHarness.commits: CommitCheck
        get() = (FcitxInputMethodService::class.java.getDeclaredField("editingSession").apply { isAccessible = true }.get(service) as EditingSession).commits

    /** A hardware space, down and up, sent to fcitx; the timestamps they carry there, down's first. */
    private fun KeyboardHarness.space(): List<Int> {
        service.onKeyDown(KeyEvent.KEYCODE_SPACE, event(KeyEvent.ACTION_DOWN))
        service.onKeyUp(KeyEvent.KEYCODE_SPACE, event(KeyEvent.ACTION_UP))
        advance(20)
        assertEquals("the space's down and up sent to fcitx", 2, api.keyTimestamps.size)
        return api.keyTimestamps.toList()
    }

    /** fcitx hands back the space's half sent with [timestamp], as it does a key it did not take. */
    private fun KeyboardHarness.handedBack(up: Boolean, timestamp: Int) =
        emit(HostEvent.Key(FcitxKeyMapping.FcitxKey_space, 0, ' '.code, up, timestamp))

    /** The reads of the text before the cursor 吗's commit makes, before any wait for the field runs out. */
    private fun KeyboardHarness.readsCommitting(): Int {
        beforeReads.clear()
        emit(HostEvent.Commit("吗", -1))
        return beforeReads.count { it == CommitCheck.READ }
    }

    /** 你好 committed and reported where it left the cursor, then a hardware space. */
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
        // the space's report held back: only the key handed back says the field changed
        h.selectionLags = true
        h.handedBack(up = false, sent[0])
        assertEquals(1, h.readsCommitting())
        assertEquals("你好 吗", h.field.text.toString())
    }

    @Test
    fun theUpOfTheSpaceThatChoseAWordHandedBackBeforeTheFieldReportsLeavesTheCommitJudged() {
        val h = KeyboardHarness().also { harness = it }
        h.start()
        // a field that takes the write and does nothing with it (CommitKeptUatTest)
        h.writesDropped = true
        val sent = h.space()
        // the down chose 你好; its up comes back with the commit, before any report could
        h.emit(HostEvent.Commit("你好", -1))
        h.handedBack(up = true, sent[1])
        h.advance(CommitCheck.WAIT_MS + 50)
        assertEquals("你好", h.commits.kept)
    }
}
