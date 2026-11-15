/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.view.KeyEvent
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.core.FakeHostApi
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A terminal's or a game's raw keys are the app's: Ctrl+Space was taken from Termux, and a Shift
 * tap switched the input method there. The user may turn the shortcuts off.
 */
class HardwareKeyboardTest {

    private val api = FakeHostApi().apply {
        availableIme = arrayOf(entry(InputMethodCatalog.PINYIN), entry(InputMethodCatalog.WUBI), entry(InputMethodCatalog.ENGLISH))
        enabledIme = availableIme
        inputMethodEntryCached = entry(InputMethodCatalog.PINYIN)
    }
    private val posted = mutableListOf<suspend HostApi.() -> Unit>()
    private var textField = true
    private var shortcutsOn = true
    private val keyboard = HardwareKeyboard({ posted += it }, { textField }, { shortcutsOn })

    private val current get() = api.inputMethodEntryCached.uniqueName

    private fun runPosted() = runBlocking {
        val jobs = posted.toList()
        posted.clear()
        jobs.forEach { it(api) }
    }

    /** Whether either of the tap's keys went no further. */
    private fun shiftTap(at: Long): Boolean {
        val down = keyboard.down(KeyEvent.KEYCODE_SHIFT_LEFT, ctrl = false, shift = true, others = false, repeat = false, timeMs = at)
        return keyboard.up(KeyEvent.KEYCODE_SHIFT_LEFT, at + 100) || down
    }

    /** Ctrl held, Shift tapped: whether any of the keys went no further. */
    private fun ctrlShift(at: Long): Boolean {
        val ctrl = keyboard.down(KeyEvent.KEYCODE_CTRL_LEFT, ctrl = false, shift = false, others = false, repeat = false, timeMs = at)
        val shift = keyboard.down(KeyEvent.KEYCODE_SHIFT_LEFT, ctrl = true, shift = false, others = false, repeat = false, timeMs = at + 30)
        val shiftUp = keyboard.up(KeyEvent.KEYCODE_SHIFT_LEFT, at + 80)
        return keyboard.up(KeyEvent.KEYCODE_CTRL_LEFT, at + 100) || ctrl || shift || shiftUp
    }

    private fun ctrlSpaceDown(at: Long) =
        keyboard.down(KeyEvent.KEYCODE_SPACE, ctrl = true, shift = false, others = false, repeat = false, timeMs = at)

    @Test
    fun aShiftTapOrCtrlSpaceSwitchesChineseAndEnglishInATextField() {
        keyboard.inputMethodChanged(InputMethodCatalog.PINYIN)
        assertFalse("the Shift goes on", shiftTap(0))
        runPosted()
        assertEquals(InputMethodCatalog.ENGLISH, current)

        assertTrue(ctrlSpaceDown(1000))
        assertTrue("its release too", keyboard.up(KeyEvent.KEYCODE_SPACE, 1050))
        runPosted()
        assertEquals(InputMethodCatalog.PINYIN, current)
    }

    @Test
    fun inAFieldOfRawKeysEveryKeyIsTheApps() {
        textField = false
        assertFalse(ctrlSpaceDown(0))
        assertFalse(keyboard.up(KeyEvent.KEYCODE_SPACE, 50))
        assertFalse(shiftTap(1000))
        assertTrue(posted.isEmpty())
    }

    @Test
    fun turnedOffTheShortcutsSwitchNothingAndTakeNoKey() {
        shortcutsOn = false
        assertFalse(ctrlSpaceDown(0))
        assertFalse(keyboard.up(KeyEvent.KEYCODE_SPACE, 50))
        assertFalse(shiftTap(1000))
        assertTrue(posted.isEmpty())
        assertEquals(InputMethodCatalog.PINYIN, current)
    }

    /** Pinyin alone enabled: Ctrl+Space is no shortcut, and goes on to the app (an editor's completion). */
    @Test
    fun ctrlSpaceWithNowhereToGoGoesOn() {
        api.enabledIme = arrayOf(entry(InputMethodCatalog.PINYIN))
        keyboard.fieldStarted()
        runPosted()
        assertFalse(ctrlSpaceDown(0))
        assertFalse(keyboard.up(KeyEvent.KEYCODE_SPACE, 50))
        assertTrue(posted.isEmpty())

        // English enabled again, seen as a field starts
        api.enabledIme = api.availableIme
        keyboard.fieldStarted()
        runPosted()
        assertTrue(ctrlSpaceDown(1000))
    }

    /** Its press went no further: let through, the app would have a Space's release alone. */
    @Test
    fun aSpaceCtrlSpaceTookStaysTakenThoughTheShortcutsGoOffBeforeItsRelease() {
        assertTrue(ctrlSpaceDown(0))
        runPosted()
        shortcutsOn = false
        assertTrue(keyboard.up(KeyEvent.KEYCODE_SPACE, 50))
        assertTrue(posted.isEmpty())
    }

    /** A taken Space's release lost (focus moved), a Space then where the shortcuts are off is the app's, release too. */
    @Test
    fun aSpaceWhereTheShortcutsAreOffLetsATakenOneGo() {
        assertTrue(ctrlSpaceDown(0))
        runPosted()
        shortcutsOn = false
        assertFalse(keyboard.down(KeyEvent.KEYCODE_SPACE, ctrl = false, shift = false, others = false, repeat = false, timeMs = 1000))
        assertFalse(keyboard.up(KeyEvent.KEYCODE_SPACE, 1050))
    }

    /** The list changed with the field kept: one Ctrl+Space goes by the old one, and the next by the new. */
    @Test
    fun aSwitchSeesTheEnabledListAgain() {
        keyboard.fieldStarted()
        runPosted()
        api.enabledIme = arrayOf(entry(InputMethodCatalog.PINYIN))
        assertTrue(ctrlSpaceDown(0))
        keyboard.up(KeyEvent.KEYCODE_SPACE, 50)
        runPosted()
        assertEquals(InputMethodCatalog.PINYIN, current)
        assertFalse(ctrlSpaceDown(1000))
    }

    /** English enabled with the field kept: the change fcitx tells is seen, so Ctrl+Space is no longer the app's. */
    @Test
    fun aListSetAnewIsSeenByTheNextCtrlSpace() {
        api.enabledIme = arrayOf(entry(InputMethodCatalog.PINYIN))
        keyboard.fieldStarted()
        runPosted()
        api.enabledIme = api.availableIme
        keyboard.inputMethodChanged(InputMethodCatalog.PINYIN)
        runPosted()
        assertTrue(ctrlSpaceDown(30))
        keyboard.up(KeyEvent.KEYCODE_SPACE, 80)
        runPosted()
        assertEquals(InputMethodCatalog.ENGLISH, current)

        // and English disabled again: Ctrl+Space goes on to the app
        api.enabledIme = arrayOf(entry(InputMethodCatalog.PINYIN))
        api.inputMethodEntryCached = entry(InputMethodCatalog.PINYIN)
        keyboard.inputMethodChanged(InputMethodCatalog.PINYIN)
        runPosted()
        assertFalse(ctrlSpaceDown(1030))
    }

    // handwriting is the toolbar's now (061-handwriting-toolbar): every input method in the list takes the keyboard
    @Test
    fun ctrlShiftGoesToTheNextInputMethod() {
        assertFalse(ctrlShift(0))
        runPosted()
        assertEquals(InputMethodCatalog.WUBI, current)
    }

    private companion object {
        fun entry(name: String) = InputMethodEntry(name, name, "", "", name, "zh_CN", "", false)
    }
}
