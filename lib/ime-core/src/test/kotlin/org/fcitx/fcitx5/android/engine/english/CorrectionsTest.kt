/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CorrectionsTest {

    private fun made(word: String, lead: String, fieldStart: Boolean = false) = Corrections.Made("x", word, lead, fieldStart, emptyList())

    @Test
    fun foundRightBeforeTheCursorWithItsSpaceOrNot() {
        val c = Corrections()
        val the = made("the", "I fed ")
        c.add(the)
        assertSame(the, c.at("I fed the ")?.made)
        assertEquals(4, c.at("I fed the ")?.length)
        assertEquals(3, c.at("Yes, I fed the")?.length)
        assertNull(c.at("I saw the "))
        assertNull(c.at("I fed the cat "))
        c.remove(the)
        assertNull(c.at("I fed the "))
    }

    // review-B S1: nothing known before it, the same word anywhere would be taken for it: not kept to
    // tap back to, a backspace right after taking it back still; at the field's start, found only there
    @Test
    fun aCorrectionWithNothingKnownBeforeItIsKeptOnlyAtTheFieldsStart() {
        val c = Corrections()
        assertEquals("the", c.made("teh", "the", "" to false, emptyList(), learning = true)?.word)
        assertNull(c.at("so the "))
        assertNull(c.at("the "))
        c.made("teh", "the", "" to true, emptyList(), learning = true)
        assertEquals(true, c.at("the") != null)
        assertNull(c.at("so the "))
        // not a field learned from: none kept, the correction made all the same
        c.clear()
        assertEquals("the", c.made("teh", "the", "I fed " to false, emptyList(), learning = false)?.word)
        assertNull(c.at("I fed the "))
        assertNull(c.made("the", "the", "I fed " to false, emptyList(), learning = true))
    }

    @Test
    fun onlyTheLastFewAreKept() {
        val c = Corrections()
        repeat(Corrections.SIZE + 1) { c.add(made("w$it", "a ")) }
        assertNull(c.at("a w0 "))
        assertEquals("w${Corrections.SIZE}", c.at("a w${Corrections.SIZE} ")?.made?.word)
    }

    // R11 F2740: what of the text before a correction tells it apart, and whether that is all of it
    @Test
    fun theLeadIsTheFieldsOwnTextCutAtLead() {
        val long = "a".repeat(Corrections.LEAD + 4)
        val cases = listOf(
            Triple("I fed ", -1, false) to ("I fed " to false),
            Triple("I fed ", -1, true) to ("I fed " to true),
            // the field cleared itself: only what came after, and that is all of it
            Triple("sent. I fed ", 6, false) to ("I fed " to true),
            Triple("sent. ", 6, false) to ("" to true),
            // longer than LEAD: its end only, never all of it
            Triple(long, -1, true) to (long.takeLast(Corrections.LEAD) to false),
            Triple("x $long", 2, false) to (long.takeLast(Corrections.LEAD) to false),
        )
        for ((given, expected) in cases) {
            assertEquals(given.toString(), expected, Corrections.lead(given.first, given.second, given.third))
        }
    }
}
