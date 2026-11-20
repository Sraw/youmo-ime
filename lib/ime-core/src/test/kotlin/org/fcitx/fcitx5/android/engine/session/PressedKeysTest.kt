/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PressedKeys]: the keys pressed for each place of the input, through every edit. */
class PressedKeysTest {
    private val keys = PressedKeys()

    @Test
    fun keysTypedAreTheirOwn() {
        keys.insert(0, "64426")
        assertEquals("64426", keys.keys(0, 5))
        assertEquals("44", keys.keys(1, 3))
        assertEquals(5, keys.length)
    }

    @Test
    fun aSyllableTakenIsItsDigitsHoweverLongItsSpelling() {
        keys.insert(0, "926")
        // 9 2 6 taken as zhan, fuzzy: four letters for three digits, then a separator
        val was = keys.runs(0, 3)
        keys.replace(0, 3, 4, "926", added = 1)
        assertEquals(5, keys.length)
        assertEquals("926", keys.keys(0, 5))
        assertEquals("926", keys.keys(0, 4))
        keys.insert(5, "4")
        assertEquals("9264", keys.keys(0, 6))
        keys.putBack(0, 5, was)
        assertEquals("9264", keys.keys(0, 4))
        assertEquals(4, keys.length)
    }

    @Test
    fun aLetterDeletedInASyllableTakenLeavesTheOthersOnTheirKeys() {
        keys.insert(0, "64")
        keys.replace(0, 2, 2, "64", added = 1)
        keys.delete(1, "ni'", NineKeyLetters.STANDARD)
        assertEquals("6", keys.keys(0, 2))
        assertEquals(2, keys.length)
    }

    @Test
    fun aKeyTypedInASyllableTakenSplitsIt() {
        keys.insert(0, "9264")
        keys.replace(0, 3, 4, "926", added = 1)
        keys.insert(2, "3", input = "zhan'4", letters = NineKeyLetters.STANDARD)
        assertEquals(7, keys.length)
        assertEquals("943264", keys.keys(0, 7))
        assertEquals("943", keys.keys(0, 3))
    }

    @Test
    fun aLetterOf连按AndAGlideAreMarked() {
        keys.insert(0, "6")
        keys.set(0, "n", multiTap = true)
        keys.insert(1, "4")
        assertTrue(keys.multiTap(0, 2))
        assertFalse(keys.multiTap(1, 2))
        assertEquals("n4", keys.keys(0, 2))
        keys.insert(2, "hao", glided = true)
        assertTrue(keys.glided(2, 5))
        assertFalse(keys.glided(0, 2))
        keys.delete(0, "N4hao", null)
        assertEquals("4hao", keys.keys(0, 4))
        keys.clear()
        assertEquals(0, keys.length)
    }

    @Test
    fun separatorsAndCaseAreNoKeys() {
        keys.insert(0, "Ni'hao")
        assertEquals("nihao", keys.keys(0, 6))
    }
}
