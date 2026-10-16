/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.view.KeyEvent
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.core.FakeFcitxAPI
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.engine.host.Engines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A terminal's or a game's raw keys are the app's: Ctrl+Space was taken from Termux, and a Shift
 * tap switched the input method there. The user may turn the shortcuts off. A key typed in
 * handwriting goes to pinyin in its own job, and one typed in pinyin posts nothing more.
 */
class HardwareKeyboardTest {

    private val api = FakeFcitxAPI().apply {
        availableIme = arrayOf(entry(Engines.PINYIN), entry(Engines.HANDWRITING), entry(Engines.ENGLISH))
        enabledIme = availableIme
        inputMethodEntryCached = entry(Engines.PINYIN)
    }
    private val posted = mutableListOf<suspend FcitxAPI.() -> Unit>()
    private var onTouch = false
    private var textField = true
    private var shortcutsOn = true
    private val keyboard = HardwareKeyboard({ posted += it }, { onTouch }, { textField }, { shortcutsOn })

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
        keyboard.inputMethodChanged(Engines.PINYIN)
        assertFalse("the Shift goes on", shiftTap(0))
        runPosted()
        assertEquals(Engines.ENGLISH, current)

        assertTrue(ctrlSpaceDown(1000))
        assertTrue("its release too", keyboard.up(KeyEvent.KEYCODE_SPACE, 1050))
        runPosted()
        assertEquals(Engines.PINYIN, current)
    }

    @Test
    fun inAFieldOfRawKeysEveryKeyIsTheApps() {
        textField = false
        assertFalse(ctrlSpaceDown(0))
        assertFalse(keyboard.up(KeyEvent.KEYCODE_SPACE, 50))
        assertFalse(shiftTap(1000))
        api.inputMethodEntryCached = entry(Engines.HANDWRITING)
        assertFalse("handwriting stays", keyboard.mayHandOff('n'.code, modified = false))
        assertTrue(posted.isEmpty())
    }

    @Test
    fun turnedOffTheShortcutsSwitchNothingAndTakeNoKey() {
        shortcutsOn = false
        assertFalse(ctrlSpaceDown(0))
        assertFalse(keyboard.up(KeyEvent.KEYCODE_SPACE, 50))
        assertFalse(shiftTap(1000))
        assertTrue(posted.isEmpty())
        assertEquals(Engines.PINYIN, current)
    }

    /** Pinyin alone enabled: Ctrl+Space is no shortcut, and goes on to the app (an editor's completion). */
    @Test
    fun ctrlSpaceWithNowhereToGoGoesOn() {
        api.enabledIme = arrayOf(entry(Engines.PINYIN))
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
        api.enabledIme = arrayOf(entry(Engines.PINYIN))
        assertTrue(ctrlSpaceDown(0))
        keyboard.up(KeyEvent.KEYCODE_SPACE, 50)
        runPosted()
        assertEquals(Engines.PINYIN, current)
        assertFalse(ctrlSpaceDown(1000))
    }

    /** English enabled with the field kept: the change fcitx tells is seen, so Ctrl+Space is no longer the app's. */
    @Test
    fun aListSetAnewIsSeenByTheNextCtrlSpace() {
        api.enabledIme = arrayOf(entry(Engines.PINYIN))
        keyboard.fieldStarted()
        runPosted()
        api.enabledIme = api.availableIme
        keyboard.inputMethodChanged(Engines.PINYIN)
        runPosted()
        assertTrue(ctrlSpaceDown(30))
        keyboard.up(KeyEvent.KEYCODE_SPACE, 80)
        runPosted()
        assertEquals(Engines.ENGLISH, current)

        // and English disabled again: Ctrl+Space goes on to the app
        api.enabledIme = arrayOf(entry(Engines.PINYIN))
        api.inputMethodEntryCached = entry(Engines.PINYIN)
        keyboard.inputMethodChanged(Engines.PINYIN)
        runPosted()
        assertFalse(ctrlSpaceDown(1030))
    }

    @Test
    fun ctrlShiftOnTheHardwareKeyboardSkipsWhatItCannotTypeIn() {
        assertFalse(ctrlShift(0))
        runPosted()
        assertEquals("handwriting skipped", Engines.ENGLISH, current)

        api.inputMethodEntryCached = entry(Engines.PINYIN)
        onTouch = true
        ctrlShift(1000)
        runPosted()
        assertEquals("the touch keyboard up", Engines.HANDWRITING, current)
    }

    @Test
    fun aShortcutAfterAHandOffKeepsWhatItChose() {
        api.inputMethodEntryCached = entry(Engines.HANDWRITING)
        runBlocking { keyboard.handOff(api) }
        assertEquals(Engines.PINYIN, current)
        shiftTap(0)
        runPosted()
        assertEquals(Engines.ENGLISH, current)

        keyboard.touchKeyboardBack()
        runPosted()
        assertEquals("not handwriting again", Engines.ENGLISH, current)
    }

    @Test
    fun aKeyTypedInHandwritingIsTypedByPinyinUntilTheTouchKeyboardIsBack() {
        api.inputMethodEntryCached = entry(Engines.HANDWRITING)
        assertTrue(keyboard.mayHandOff('n'.code, modified = false))
        runBlocking { keyboard.handOff(api) }
        assertEquals(Engines.PINYIN, current)
        // in the key's own job: none of its own
        assertTrue(posted.isEmpty())

        keyboard.touchKeyboardBack()
        runPosted()
        assertEquals(Engines.HANDWRITING, current)
    }

    @Test
    fun aKeyTypedInPinyinChangesNothing() {
        api.calls.clear()
        runBlocking { keyboard.handOff(api) }
        assertEquals(emptyList<String>(), api.calls)
        assertEquals(Engines.PINYIN, current)
    }

    @Test
    fun onlyACharacterTypedOnTheHardwareKeyboardIsHandedOff() {
        assertFalse("Space", keyboard.mayHandOff(' '.code, modified = false))
        assertFalse("Enter", keyboard.mayHandOff('\n'.code, modified = false))
        assertFalse("Delete", keyboard.mayHandOff(0x7f, modified = false))
        assertFalse("Ctrl+N", keyboard.mayHandOff('n'.code, modified = true))
        onTouch = true
        assertFalse("the touch keyboard up", keyboard.mayHandOff('n'.code, modified = false))
    }

    private companion object {
        fun entry(name: String) = InputMethodEntry(name, name, "", "", name, "zh_CN", "", false)
    }
}
