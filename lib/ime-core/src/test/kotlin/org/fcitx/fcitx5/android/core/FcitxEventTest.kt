/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The events' data classes: equality by content, the UI's views of entries and words, key states.
 */
class FcitxEventTest {

    private val word = CandidateWord("1", "你好", "nǐ hǎo")
    private val entry = InputMethodEntry("pinyin", "拼音", "", "", "拼", "zh_CN", "androidengine", true, "", "", "")
    private val action = Action(1, false, true, false, "chttrans", "", "简", "简繁", null)

    @Test
    fun arraysAreComparedByWhatTheyHold() {
        fun candidates(vararg words: CandidateWord) = FcitxEvent.CandidateListEvent.Data(words.size, arrayOf(*words))
        assertNotEquals(candidates(word), candidates(CandidateWord.Empty))
        val other = action.copy(menu = arrayOf(action))
        assertNotEquals(action, other)
        assertEquals(other, action.copy(menu = arrayOf(action.copy())))
    }

    @Test
    fun entriesAndWordsAsTheUiShowsThem() {
        assertEquals("你好 nǐ hǎo", word.textWithComment())
        assertEquals("你好nǐ hǎo", word.copy(spaceBetweenComment = false).textWithComment())
        assertEquals("你好", word.copy(comment = " ").textWithComment())
        assertEquals("拼音", entry.displayName)
        assertEquals("x", InputMethodEntry("x").displayName)
        assertEquals(InputMethodSubMode(), InputMethodEntry("a", "b", "", "", "", "", "", false).subMode)
        assertEquals("0x0061", KeySym(0x61).toString())
    }

    @Test
    fun keyStatesCombineAsFcitxsBits() {
        val states = KeyStates(KeyState.Ctrl, KeyState.Alt, KeyState.Meta, KeyState.NumLock, KeyState.CapsLock)
        assertTrue(states.ctrl && states.alt && states.meta && states.numLock && states.capsLock)
        assertFalse(states.shift || states.virtual)
        assertTrue(KeyStates.Virtual.virtual)
        assertEquals(KeyState.Ctrl_Shift.state, (0u + KeyState.Ctrl) + KeyState.Shift)
        assertEquals(KeyState.Ctrl.state, KeyState.Ctrl_Shift.state - KeyState.Shift)
        assertEquals(KeyState.Ctrl.state.toInt(), KeyStates.of(KeyState.Ctrl.state.toInt()).toInt())
        assertEquals(KeyStates.Empty, KeyStates(0u))
    }
}
