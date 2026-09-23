/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodePointsTest {

    private val wave = String(Character.toChars(0x1F44B)) // a surrogate pair
    private val high = wave[0]
    private val low = wave[1]

    @Test
    fun plainTextIsOneUnitPerCodePoint() {
        assertEquals(2, CodePoints.lengthOfLast("abcd", 2))
        assertEquals(2, CodePoints.lengthOfFirst("abcd", 2))
    }

    @Test
    fun aSurrogatePairIsTwoUnits() {
        assertEquals(2, CodePoints.lengthOfLast("a$wave", 1))
        assertEquals(2, CodePoints.lengthOfFirst("${wave}a", 1))
        assertEquals(3, CodePoints.lengthOfLast("a$wave", 2))
        assertEquals(3, CodePoints.lengthOfFirst("${wave}a", 2))
    }

    @Test
    fun mixedTextIsMeasuredPerCodePoint() {
        val text = "${wave}b$wave"
        assertEquals(5, CodePoints.lengthOfLast(text, 3))
        assertEquals(3, CodePoints.lengthOfLast(text, 2))
        assertEquals(3, CodePoints.lengthOfFirst(text, 2))
    }

    @Test
    fun askingForMoreThanThereIsGivesTheWholeText() {
        assertEquals(3, CodePoints.lengthOfLast("a$wave", 10))
        assertEquals(3, CodePoints.lengthOfFirst("a$wave", 10))
        assertEquals(0, CodePoints.lengthOfLast("", 1))
        assertEquals(0, CodePoints.lengthOfFirst("", 1))
    }

    @Test
    fun zeroCodePointsIsZeroUnits() {
        assertEquals(0, CodePoints.lengthOfLast("a$wave", 0))
        assertEquals(0, CodePoints.lengthOfFirst("a$wave", 0))
    }

    /**
     * An unpaired surrogate is malformed text, which the editor refuses to delete by code point,
     * so there is no length: null. Includes surrogates in the wrong order, and a half pair next
     * to an ordinary character.
     */
    @Test
    fun anUnpairedSurrogateHasNoLength() {
        assertNull(CodePoints.lengthOfLast("a$high", 1))
        assertNull(CodePoints.lengthOfLast("$low", 1))
        assertNull(CodePoints.lengthOfFirst("${low}a", 1))
        assertNull(CodePoints.lengthOfFirst("$high", 1))
        assertNull("reversed order is not a pair", CodePoints.lengthOfLast("$low$high", 1))
        assertNull("a letter then a low surrogate is not a pair", CodePoints.lengthOfLast("a$low", 1))
        assertNull("a high surrogate then a letter is not a pair", CodePoints.lengthOfFirst("${high}a", 1))
    }

    /** Only the code points counted matter: malformed text further away is never reached. */
    @Test
    fun anUnpairedSurrogateBeyondTheCountDoesNotMatter() {
        assertEquals(1, CodePoints.lengthOfLast("${low}ab", 1))
        assertEquals(1, CodePoints.lengthOfFirst("ab$high", 1))
    }
}
