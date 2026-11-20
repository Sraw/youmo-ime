/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class KeyEventRelayTest {

    private class RecordingConnection : BaseInputConnection(View(RuntimeEnvironment.getApplication()), false) {
        val sent = mutableListOf<KeyEvent>()
        val cleared = mutableListOf<Int>()

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            sent += event
            return true
        }

        override fun clearMetaKeyStates(states: Int): Boolean {
            cleared += states
            return true
        }
    }

    private val ic = RecordingConnection()
    private var connection: InputConnection? = ic
    private val relay = KeyEventRelay { connection }

    private fun key(code: Int, action: Int = KeyEvent.ACTION_DOWN, meta: Int = 0) =
        KeyEvent(1L, 1L, action, code, 0, meta)

    @Test
    fun aRememberedEventIsReplayedToTheEditorAsTheSameObject() {
        val event = key(KeyEvent.KEYCODE_A)
        val id = relay.remember(event)

        assertTrue(relay.replay(id))

        assertSame(event, ic.sent.single())
    }

    @Test
    fun anEventCanOnlyBeReplayedOnce() {
        val id = relay.remember(key(KeyEvent.KEYCODE_A))
        assertTrue(relay.replay(id))
        assertFalse(relay.replay(id))
        assertEquals(1, ic.sent.size)
    }

    @Test
    fun anUnknownIdIsNotReplayedSoTheCallerCanMakeUpAnEvent() {
        assertFalse(relay.replay(42))
        assertTrue(ic.sent.isEmpty())
    }

    @Test
    fun theSameEventTwiceGetsTwoIds() {
        // down and up can share a timestamp, which is why ids are a counter
        val event = key(KeyEvent.KEYCODE_A)
        assertNotEquals(relay.remember(event), relay.remember(event))
    }

    @Test
    fun theOldestEventsAreEvictedPastCapacity() {
        val first = relay.remember(key(KeyEvent.KEYCODE_A))
        repeat(KeyEventRelay.CAPACITY) { relay.remember(key(KeyEvent.KEYCODE_B)) }
        assertFalse("the first was pushed out", relay.replay(first))
    }

    @Test
    fun clearForgetsPendingEventsAndNeverReusesTheirIds() {
        val id = relay.remember(key(KeyEvent.KEYCODE_A))
        relay.clear()
        assertFalse(relay.replay(id))
        assertNotEquals("a late reply for the old key must not hit a new one", id, relay.remember(key(KeyEvent.KEYCODE_B)))
    }

    @Test
    fun aKeyThatWouldOpenTheCharacterPickerIsReplayedWithoutItsCharacter() {
        // no real KeyEvent maps to PICKER_DIALOG_INPUT in Robolectric's key character map
        val event = mockk<KeyEvent>(relaxed = true) {
            every { unicodeChar } returns ForwardedKeys.PICKER_DIALOG_INPUT
            every { keyCode } returns KeyEvent.KEYCODE_A
            every { action } returns KeyEvent.ACTION_DOWN
            every { downTime } returns 5L
            every { eventTime } returns 6L
            every { scanCode } returns 30
        }

        assertTrue(relay.replay(relay.remember(event)))

        val sent = ic.sent.single()
        assertEquals(KeyEvent.KEYCODE_A, sent.keyCode)
        assertEquals("rebuilt as a virtual-keyboard key", -1, sent.deviceId)
        assertEquals(30, sent.scanCode)
        // deviceId -1 picks the virtual keyboard's character map, which has no picker key
        assertNotEquals(ForwardedKeys.PICKER_DIALOG_INPUT, sent.unicodeChar)
        assertTrue(ic.cleared.isEmpty())
    }

    @Test
    fun releasingAModifierClearsExactlyTheMetaBitsItDropped() {
        val shiftDown = key(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_DOWN, KeyEvent.META_SHIFT_ON)
        val shiftUp = key(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_UP, 0)

        relay.replay(relay.remember(shiftDown))
        relay.replay(relay.remember(shiftUp))

        assertEquals(listOf(KeyEvent.META_SHIFT_ON), ic.cleared)
    }

    @Test
    fun aPlainKeyLeavesMetaStateAlone() {
        relay.replay(relay.remember(key(KeyEvent.KEYCODE_A)))
        assertTrue(ic.cleared.isEmpty())
    }

    @Test
    fun withNoEditorAnEventIsStillConsumedAndModifiersStillTracked() {
        connection = null
        val id = relay.remember(key(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_DOWN, KeyEvent.META_SHIFT_ON))
        assertTrue("consumed: the caller must not also synthesise it", relay.replay(id))

        connection = ic
        relay.replay(relay.remember(key(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_UP, 0)))
        assertEquals(listOf(KeyEvent.META_SHIFT_ON), ic.cleared)
    }
}
