/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.OwnKeysTurn.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Other groups of a layout's own take over at their first code, what was typed committed then (061-custom-layout §6, L4). */
class OwnKeysTurnTest {
    private val a = listOf("qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m", "pq rs", "abc", "xyz")
    private val b = a.reversed()
    private val code = NineKeyLetters.CUSTOM_BLOCK.first
    private val place = Keyboard.PLACES.first
    private val backspace = 0x08
    private val space = ' '.code

    @Test
    fun theFirstGroupsOrTheSameOnesTakeOverAtOnce() {
        for (key in listOf(code, place, backspace, 'a'.code)) {
            assertEquals(Step.TURN, OwnKeysTurn.at(null, a, key))
            assertEquals(Step.TURN, OwnKeysTurn.at(a, a, key))
            assertEquals(Step.TURN, OwnKeysTurn.at(null, null, key))
        }
        // the same groups told anew (another list, equal): no change
        assertEquals(Step.TURN, OwnKeysTurn.at(a, a.toList(), backspace))
    }

    @Test
    @Uat("PY-155")
    fun otherGroupsWaitForTheirFirstCodeAndCommitThen() {
        for (key in listOf(backspace, space, '\''.code, '1'.code, 'a'.code, NineKeyLetters.G18.codes[0].code)) {
            assertEquals("key $key", Step.WAIT, OwnKeysTurn.at(a, b, key))
        }
        assertEquals(Step.COMMIT_AND_TURN, OwnKeysTurn.at(a, b, code))
        assertEquals(Step.COMMIT_AND_TURN, OwnKeysTurn.at(a, b, NineKeyLetters.CUSTOM_BLOCK.last))
        assertEquals(Step.COMMIT_AND_TURN, OwnKeysTurn.at(a, b, place))
    }

    // review L4 N1: none told after some (a fallback took them back) waits as other groups do: a Backspace edits what
    // was typed; the built-in codes the keyboard shown sends turn as KeyboardPair turns
    @Test
    fun noneToldAfterSomeWaitsAsOtherGroupsDo() {
        for (key in listOf(backspace, space, 'a'.code, NineKeyLetters.G14.codes[0].code)) {
            assertEquals("key $key", Step.WAIT, OwnKeysTurn.at(a, null, key))
        }
        assertEquals(Step.COMMIT_AND_TURN, OwnKeysTurn.at(a, null, code))
    }

    @Test
    fun aCodeOfOnesOwnIsTheBlockOrAPlace() {
        assertTrue(OwnKeysTurn.isCode(code))
        assertTrue(OwnKeysTurn.isCode(place))
        assertTrue(OwnKeysTurn.isCode(Keyboard.PLACES.last))
        assertFalse(OwnKeysTurn.isCode(NineKeyLetters.G14.codes[0].code))
        assertFalse(OwnKeysTurn.isCode('a'.code))
        assertFalse(OwnKeysTurn.isCode(backspace))
    }
}
