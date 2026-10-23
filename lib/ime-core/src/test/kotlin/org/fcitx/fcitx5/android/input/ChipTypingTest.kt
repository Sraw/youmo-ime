/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChipTypingTest {

    private fun shownWith(editor: String, bar: String) = ChipTyping().apply {
        editorPreedit(editor)
        barPreedit(bar)
        chipShown()
    }

    @Test
    fun theSamePreeditShownAnewKeepsTheChip() {
        val t = shownWith("ni", "ni hao")
        assertFalse(t.editorPreedit("ni"))
        assertFalse(t.barPreedit("ni hao"))
    }

    @Test
    fun aPreeditEditedHidesIt() {
        assertTrue(shownWith("ni", "ni hao").editorPreedit("nih"))
        assertTrue(shownWith("ni", "ni hao").barPreedit("ni hao m"))
    }

    @Test
    fun aKeyOrAWordPutInHidesIt() {
        val t = shownWith("", "")
        assertTrue(t.typed())
        // the preedit back as it was is still typing since the chip
        assertTrue(t.editorPreedit(""))
    }

    @Test
    fun aKeysReleaseIsNoTyping() {
        val t = shownWith("", "")
        // F3554: the shortcut that raised a tip (Ctrl+.), coming back up: the tip stays
        assertFalse(t.key(up = true))
        assertFalse(t.editorPreedit(""))
        // a key pressed is typing
        assertTrue(t.key(up = false))
    }

    @Test
    fun aNewChipCountsFromItsOwnPreedit() {
        val t = shownWith("a", "")
        t.typed()
        t.chipShown()
        assertFalse(t.editorPreedit("a"))
    }
}
