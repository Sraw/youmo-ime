/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A tap on the code row puts the cursor where it is in the code: one off is a key off, unseen. */
class CodeRowTest {

    @Test
    fun aTapIsThePlaceInTheCodeItLandsOn() = assertRows(
        "a tap is the place in the code" to {
            assertEquals(2, CodeRow.tapAt(2, cursor = -1, aux = 0, preedit = "nihao"))
            assertEquals(5, CodeRow.tapAt(5, cursor = -1, aux = 0, preedit = "nihao"))
        },
        "the cursor drawn is no character of the code" to {
            // ni|hao
            assertEquals(2, CodeRow.tapAt(2, cursor = 2, aux = 0, preedit = "nihao"))
            assertEquals(2, CodeRow.tapAt(3, cursor = 2, aux = 0, preedit = "nihao"))
            assertEquals(3, CodeRow.tapAt(4, cursor = 2, aux = 0, preedit = "nihao"))
            assertEquals(5, CodeRow.tapAt(6, cursor = 2, aux = 0, preedit = "nihao"))
        },
        "the aux before the code is no part of it" to {
            // [拼]ni|hao
            assertEquals(0, CodeRow.tapAt(1, cursor = 5, aux = 3, preedit = "nihao"))
            assertEquals(2, CodeRow.tapAt(5, cursor = 5, aux = 3, preedit = "nihao"))
            assertEquals(2, CodeRow.tapAt(6, cursor = 5, aux = 3, preedit = "nihao"))
            assertEquals(3, CodeRow.tapAt(7, cursor = 5, aux = 3, preedit = "nihao"))
        },
        "a tap on the aux after the code or past its end is its end" to {
            // nihao  ni'hao
            assertEquals(5, CodeRow.tapAt(9, cursor = -1, aux = 0, preedit = "nihao"))
            assertEquals(5, CodeRow.tapAt(40, cursor = 2, aux = 0, preedit = "nihao"))
        },
        "a tap inside a surrogate pair is after its character" to {
            // a😀b, the tap between the emoji's two halves: the cursor never splits a character
            assertEquals(2, CodeRow.tapAt(2, cursor = -1, aux = 0, preedit = "a\uD83D\uDE00b"))
        },
        "the cursor is in code points the tap in UTF-16 units" to {
            val preedit = "a\uD83D\uDE00b"
            assertEquals(1, CodeRow.tapAt(1, cursor = -1, aux = 0, preedit = preedit))
            assertEquals(2, CodeRow.tapAt(3, cursor = -1, aux = 0, preedit = preedit))
            assertEquals(3, CodeRow.tapAt(4, cursor = -1, aux = 0, preedit = preedit))
            // a😀|b
            assertEquals(2, CodeRow.tapAt(4, cursor = 3, aux = 0, preedit = preedit))
        },
    )

    @Test
    fun theRowScrollsOnlyForTheCursorOutOfSight() {
        assertNull(CodeRow.scrollFor(100, margin = 32, scroll = 0, seen = 300))
        // past the right edge, and before the left: the margin of text beside it in sight too
        assertEquals(52, CodeRow.scrollFor(320, margin = 32, scroll = 0, seen = 300))
        assertEquals(108, CodeRow.scrollFor(140, margin = 32, scroll = 200, seen = 300))
        // never before the start
        assertEquals(0, CodeRow.scrollFor(10, margin = 32, scroll = 50, seen = 300))
    }
}
