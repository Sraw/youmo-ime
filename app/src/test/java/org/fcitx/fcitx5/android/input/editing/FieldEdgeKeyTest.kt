/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The edit panel's |< went to the start of the line in a multi-line field, not of the field
 * (TB-06): it sent a bare Home, which a text view reads as the line's start; Ctrl+Home is the
 * field's (ArrowKeyMovementMethod).
 */
class FieldEdgeKeyTest {

    @Test
    fun homeAndEndGoToTheFieldsEdgesWithCtrl() {
        assertTrue(fieldEdgeCtrl(rawKeyInput = false))
    }

    // F2378: a terminal reads Ctrl+Home as ESC[1;5H, which a shell echoes; a bare Home moves
    @Test
    fun aRawKeyFieldGetsThemBare() {
        assertFalse(fieldEdgeCtrl(rawKeyInput = true))
    }

    // TB-06: 选择 stayed on after 复制, so >| extended the selection and 粘贴 replaced it
    @Test
    fun aCopyLeavesTheCursorAtTheSelectionsEnd() {
        assertEquals(3, cursorAfterCopy(0, 3))
        assertEquals("selected backwards", 3, cursorAfterCopy(3, 0))
        // F2376: nothing selected, the cursor is left alone
        assertNull(cursorAfterCopy(2, 2))
    }

    @Test
    fun aCopyIsSaidExceptInAHiddenPassword() {
        fun info(type: Int) = android.view.inputmethod.EditorInfo().apply { inputType = type }
        val text = android.text.InputType.TYPE_CLASS_TEXT
        assertTrue(copyIsSaid(info(text)))
        assertTrue("shown password copies", copyIsSaid(info(text or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)))
        assertFalse(copyIsSaid(info(text or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)))
        assertFalse(copyIsSaid(info(text or android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)))
    }
}
