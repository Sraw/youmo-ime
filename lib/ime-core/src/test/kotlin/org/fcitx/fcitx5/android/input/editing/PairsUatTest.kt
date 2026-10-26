/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.Uat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * uat/02-keyboard.md's brackets: what the service does with the long-pressed bracket (the pairs
 * first, else committed) and with ⌫ (the pair's first, then the deletion), on a FakeEditor.
 */
class PairsUatTest {

    private val editor = FakeEditor("", 0)
    private val session = EditingSession(editor).also { it.selection.resetTo(0) }

    private fun type(text: String) {
        if (!session.pairs.type(text)) session.commitText(text)
    }

    private fun backspace() {
        session.pairs.backspace()
        assertTrue(session.backspace(EditorTraits(acceptsDeleteSurrounding = true)))
    }

    private val shown get() = editor.text.substring(0, editor.selectionStart) + "|" + editor.text.substring(editor.selectionStart)

    @Test
    @Uat("KB-26")
    fun aBracketComesInPairsTheCursorBetween() {
        type("（")
        assertEquals("（|）", shown)
    }

    @Test
    @Uat("KB-27")
    fun theClosingBracketStepsOverTheOneThere() {
        type("（")
        // ni 空格: 你 committed
        type("你")
        type("）")
        assertEquals("（你）|", shown)
    }

    @Test
    @Uat("KB-28")
    fun backspaceRightAfterDeletesThePair() {
        type("（")
        backspace()
        assertEquals("|", shown)
    }
}
