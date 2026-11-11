/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import org.fcitx.fcitx5.android.input.picker.EmojiSearchQuery.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiSearchQueryTest {

    private val query = EmojiSearchQuery()

    @Test
    fun lettersBuildTheQueryAndABackspaceTakesTheLast() {
        assertEquals(Outcome.CHANGED, query.type("x"))
        assertEquals(Outcome.CHANGED, query.type("ia"))
        assertEquals("xia", query.text)
        assertEquals(Outcome.CHANGED, query.backspace(repeat = false))
        assertEquals(Outcome.CHANGED, query.backspace(repeat = true))
        assertEquals("x", query.text)
        query.clear()
        assertEquals("", query.text)
    }

    @Test
    fun aHeldBackspaceStopsAtAnEmptyQuery() {
        query.type("xi")
        // held on from the letters: they go, the field's text does not
        assertEquals(Outcome.CHANGED, query.backspace(repeat = true))
        assertEquals(Outcome.CHANGED, query.backspace(repeat = true))
        assertEquals(Outcome.SWALLOWED, query.backspace(repeat = true))
        assertEquals("", query.text)
    }

    @Test
    fun aSingleBackspaceOnAnEmptyQueryIsTheFields() {
        assertEquals(Outcome.PASSED, query.backspace(repeat = false))
        assertEquals("", query.text)
    }

    // ⌫'s slots (060-keys-unify J1): the query's, not the field's
    @Test
    fun clearAllThenRestoreOnce() {
        query.type("xiao")
        assertEquals(Outcome.CHANGED, query.clearAll())
        assertEquals("", query.text)
        query.type("m")
        assertEquals(Outcome.CHANGED, query.restore())
        assertEquals("in place of what was typed since", "xiao", query.text)
        assertEquals(Outcome.SWALLOWED, query.restore())
        assertEquals("xiao", query.text)
    }

    @Test
    fun clearAllOfNothingAndRestoreAfterReopeningDoNothing() {
        assertEquals("an empty query: nothing, the field's text kept", Outcome.SWALLOWED, query.clearAll())
        query.type("xi")
        query.clearAll()
        query.clear()
        assertEquals(Outcome.SWALLOWED, query.restore())
        assertEquals("", query.text)
    }
}
