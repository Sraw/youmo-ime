/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.input.editing.FakeEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The editor as BaseInputConnection's, Shift+Ctrl+Home / End as a TextView's: the selection extended from its anchor. */
class SelectToEdgeTest {

    private fun FakeEditor.selectTo(toStart: Boolean) {
        SelectToEdge.collapseAt(selectionStart, selectionEnd, toStart)?.let { setSelection(it, it) }
        setSelection(selectionStart, if (toStart) 0 else text.length)
    }

    private fun FakeEditor.selected() = text.substring(minOf(selectionStart, selectionEnd), maxOf(selectionStart, selectionEnd))

    @Test
    @Uat("KB-116")
    fun fromTheMiddleEachSelectsToItsEdge() {
        val start = FakeEditor("hello world", cursor = 5).apply { selectTo(toStart = true) }
        assertEquals("hello", start.selected())
        val end = FakeEditor("hello world", cursor = 5).apply { selectTo(toStart = false) }
        assertEquals(" world", end.selected())
        assertEquals("the edge the moving end", 11, end.selectionEnd)
    }

    @Test
    fun atItsEdgeAlreadyNothingIsSelected() {
        assertEquals("", FakeEditor("hello", cursor = 0).apply { selectTo(toStart = true) }.selected())
        assertEquals("", FakeEditor("hello", cursor = 5).apply { selectTo(toStart = false) }.selected())
        assertEquals("", FakeEditor("").apply { selectTo(toStart = true) }.selected())
        assertEquals("", FakeEditor("").apply { selectTo(toStart = false) }.selected())
    }

    // Shift alone would keep the anchor, the near end where the selection was made towards the far one
    @Test
    @Uat("KB-116")
    fun aSelectionKeepsItsEndFartherFromTheEdgeWhicheverWayItWasMade() {
        for ((a, b) in listOf(2 to 5, 5 to 2)) {
            val toStart = FakeEditor("hello world").apply { setSelection(a, b); selectTo(toStart = true) }
            assertEquals("$a..$b", "hello", toStart.selected())
            val toEnd = FakeEditor("hello world").apply { setSelection(a, b); selectTo(toStart = false) }
            assertEquals("$a..$b", "llo world", toEnd.selected())
        }
    }

    @Test
    fun theCursorIsPutOnTheFarEndOnlyForASelection() {
        assertNull(SelectToEdge.collapseAt(3, 3, toStart = true))
        assertEquals(7, SelectToEdge.collapseAt(3, 7, toStart = true))
        assertEquals(7, SelectToEdge.collapseAt(7, 3, toStart = true))
        assertEquals(3, SelectToEdge.collapseAt(7, 3, toStart = false))
        assertNull("not known", SelectToEdge.collapseAt(-1, 3, toStart = false))
    }
}
