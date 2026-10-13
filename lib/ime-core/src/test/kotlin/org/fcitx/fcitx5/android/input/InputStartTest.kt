/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputStartTest {

    // a select-all-on-focus field (an address bar) the user typed in, then turned: its EditorInfo
    // still has the whole text selected, the editor only the cursor, and the pinyin is taken out
    @Test
    fun theScreenTurnedTheSelectionFollowedCountsNotTheOneTheFieldStartedWith() {
        val turned = InputStart(again = true, followedSelected = false, editorComposing = true, initialSelStart = 0, initialSelEnd = 12)
        assertFalse(turned.selected)
        assertTrue(turned.editorComposes)
        // and text the user selected since is selected, whatever the field started with
        assertTrue(InputStart(again = true, followedSelected = true, editorComposing = false, initialSelStart = 3, initialSelEnd = 3).selected)
    }

    // an app that set new text and restarted input: its EditorInfo is the editor's now, which reported nothing of the new text yet
    @Test
    fun anAppRestartingInputIsTakenAtItsEditorInfo() {
        val restarted = InputStart(again = false, followedSelected = false, editorComposing = true, initialSelStart = 0, initialSelEnd = 5)
        assertTrue(restarted.selected)
        assertFalse(restarted.editorComposes)
        assertFalse(InputStart(again = false, followedSelected = true, editorComposing = false, initialSelStart = 5, initialSelEnd = 5).selected)
    }

    // the text before the cursor after a turn is not what the field had as it first started
    @Test
    fun theScreenTurnedTheInitialTextIsTheFirstStartsAndNotRead() {
        assertFalse(InputStart(again = true, followedSelected = false, editorComposing = false, initialSelStart = 4, initialSelEnd = 4).initialTextIsNow)
        assertTrue(InputStart(again = false, followedSelected = false, editorComposing = false, initialSelStart = 4, initialSelEnd = 4).initialTextIsNow)
    }
}
