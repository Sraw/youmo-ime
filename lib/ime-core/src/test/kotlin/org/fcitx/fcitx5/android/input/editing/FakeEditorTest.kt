/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FakeEditorTest {

    /** As `InputConnectionEditor` with no connection, every call is dropped, so no test passes on an edit a real editor would not make. */
    @Test
    fun withNoConnectionNothingIsEditedOrRead() {
        val e = FakeEditor("abcd", cursor = 2)
        e.setComposingRegion(1, 2)
        e.calls.clear()
        e.isAvailable = false
        e.commitText("x", 1)
        e.setComposingText("y", 1)
        e.setSelection(0, 0)
        e.finishComposingText()
        e.setComposingRegion(0, 4)
        e.deleteSurroundingText(1, 1, inCodePoints = false)
        assertEquals("abcd", e.text)
        assertEquals(2 to 2, e.selectionStart to e.selectionEnd)
        assertEquals(1 to 2, e.composingStart to e.composingEnd)
        assertNull(e.textBeforeCursor(1))
        assertNull(e.textAfterCursor(1))
        assertEquals("every call is still recorded", 8, e.calls.size)
    }
}
