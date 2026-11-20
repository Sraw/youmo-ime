/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardAdapterTest {

    private val b = ClipboardEntry.BULLET

    private fun excerpt(text: String, mask: Boolean = false, chars: Int = 128) =
        ClipboardAdapter.excerptText(text, mask, chars = chars)

    @Test
    fun aMaskedEntryKeepsItsLineBreaks() {
        assertEquals("ab\ncde", excerpt("ab\ncde"))
        assertEquals("$b$b\n$b$b$b", excerpt("ab\ncde", mask = true))
    }

    @Test
    fun aLineLongerThanTheExcerptIsCutAndTheNextLineFollows() {
        assertEquals("abc\ngh", excerpt("abcdef\ngh", chars = 3))
        assertEquals("$b$b$b\n$b$b", excerpt("abcdef\ngh", mask = true, chars = 3))
    }

    @Test
    fun aLineReachingPastWhatTheExcerptCouldShowEndsIt() {
        assertEquals("aaa", excerpt("a".repeat(20) + "\nb", chars = 3))
        assertEquals("$b$b$b", excerpt("a".repeat(20) + "\nb", mask = true, chars = 3))
    }

    @Test
    fun aLineOfExactlyTheExcerptLengthKeepsTheNextLine() {
        assertEquals("abc\ngh", excerpt("abc\ngh", chars = 3))
    }
}
