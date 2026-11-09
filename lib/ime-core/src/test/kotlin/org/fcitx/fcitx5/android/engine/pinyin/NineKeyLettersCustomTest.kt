/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Any layout's letter groups as a grouped keyboard ([NineKeyLetters.custom]) and a tap's place on a key's letters in two dimensions (060-render-p3 T9a). */
class NineKeyLettersCustomTest {

    // the 17 keys of the sample layout (keyboard-scene's commonTest data k17.xml)
    private val k17 = listOf("qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m", "pq rs", "abc", "xyz")

    @Test
    fun oneRowOfTwoGoesAgainstALetterAsTheOneDimensionalRuleAtEveryPlace() {
        val row = listOf("bn")
        for (step in 0..100) {
            val x = step / 100f
            for (index in 0..1) {
                val old = NineKeyLetters.G14.against(index, x)
                for (y in listOf(0f, 0.25f, 0.5f, 1f)) assertEquals("$index at $x, $y", old, NineKeyLetters.against(index, x, y, row), 0f)
                // the key aware rule on the 14 keys: the one dimensional place and the two dimensional one alike
                val code = NineKeyLetters.G14.key('b')
                assertEquals(old, NineKeyLetters.G14.against(code, index, x), 0f)
                // a place a key sends: across to the nearest of 17
                val sent = Math.round(x * 16) / 16f
                assertEquals(NineKeyLetters.G14.against(index, sent), NineKeyLetters.G14.against(code, index, NineKeyLetters.place(sent, 0.5f)), 0f)
            }
        }
    }

    @Test
    fun aLetterGoesAgainstATapByItsDistanceOverTheFarthestCorners() {
        val square = listOf("pq", "rs")
        // on its own cell: nothing; at the far corner: all of it
        assertEquals(0f, NineKeyLetters.against(0, 0.2f, 0.2f, square), 0f)
        assertEquals(1f, NineKeyLetters.against(0, 1f, 1f, square), 1e-6f)
        // r is below p: straight down to the bottom edge, half its farthest corner's distance (0.5 over √0.5)
        assertEquals(0.5f / kotlin.math.sqrt(0.5f), NineKeyLetters.against(0, 0.25f, 1f, square), 1e-6f)
        assertEquals(0f, NineKeyLetters.against(2, 0.25f, 1f, square), 0f)
        // a row of three: each its third
        val three = listOf("abc")
        assertEquals(0f, NineKeyLetters.against(1, 0.5f, 0.5f, three), 0f)
        // from the far third's middle: half a key over two thirds
        assertEquals(0.75f, NineKeyLetters.against(0, 2 / 3f + 1 / 6f, 0.5f, three), 1e-6f)
        // a single letter is the whole key
        assertEquals(0f, NineKeyLetters.against(0, 1f, 0f, listOf("m")), 0f)
    }

    @Test
    fun aPlaceKeepsItsTwoDimensions() {
        for (xs in 0..16) for (ys in 0..8) {
            val x = xs / 16f
            val y = ys / 8f
            val place = NineKeyLetters.place(x, y)
            assertEquals(x, NineKeyLetters.placeX(place), 0f)
            assertEquals(y, NineKeyLetters.placeY(place), 0f)
        }
        // a one dimensional place is its x, no y
        assertEquals(0.75f, NineKeyLetters.placeX(0.75f), 0f)
        assertTrue(NineKeyLetters.placeY(0.75f).isNaN())
    }

    @Test
    fun groupsOfABuiltInKeyboardAreThatKeyboardAndItsRecord() {
        assertSame(NineKeyLetters.G14, NineKeyLetters.custom(NineKeyLetters.G14.tag().split(',')))
        assertSame(NineKeyLetters.G18, NineKeyLetters.custom(NineKeyLetters.G18.tag().split(',')))
        // written in rows, the same letters: the same keyboard
        val rows = NineKeyLetters.G14.tag().split(',').map { it.toList().joinToString(" ") }
        assertSame(NineKeyLetters.G14, NineKeyLetters.custom(rows))
    }

    @Test
    fun anyOtherGroupsAreAKeyboardOfTheirOwnCodes() {
        val made = NineKeyLetters.custom(k17)
        assertTrue(made.isGrouped)
        assertEquals(k17.joinToString(",") { it.replace(" ", "") }, made.tag())
        assertEquals(k17.size, made.codes.length)
        assertTrue(made.codes.all { it.code in NineKeyLetters.CUSTOM_BLOCK })
        assertEquals("pqrs", made.letters(made.codes[14]))
        assertEquals(listOf("pq", "rs"), made.rowsOf(made.codes[14]))
        assertEquals(listOf("bn"), made.rowsOf(made.codes[12]))
        // p is on two keys: two ways to type it
        assertEquals(2, made.keysOf('p').length)
        // a code read back: the keyboard in use, which making one does not set
        val other = NineKeyLetters.custom(k17.reversed())
        NineKeyLetters.inUse(made)
        assertSame(made, NineKeyLetters.ofCode(made.codes[0].code))
        NineKeyLetters.custom(k17.reversed())
        assertSame(made, NineKeyLetters.ofCode(made.codes[0].code))
        NineKeyLetters.inUse(other)
        assertSame(other, NineKeyLetters.ofCode(made.codes[0].code))
        // past its keys: none
        val small = NineKeyLetters.custom(listOf("abcde", "fghij", "klmno", "pqrst", "uvwxy", "z"))
        NineKeyLetters.inUse(small)
        assertNull(NineKeyLetters.ofCode(made.codes[6].code))
        NineKeyLetters.inUse(made)
        // with rows or without, the same keyboard
        assertEquals(made, NineKeyLetters.custom(k17.map { it.replace(" ", "") }))
        // its record found by its tag
        assertEquals(made.tag(), NineKeyLetters.filedAs(made.tag())?.tag())
        assertEquals(made.codes, NineKeyLetters.filedAs(made.tag())?.codes)
        // reading a record does not change which keyboard a code is read as
        assertSame(made, NineKeyLetters.ofCode(made.codes[0].code))
        assertSame(NineKeyLetters.G14, NineKeyLetters.filedAs(NineKeyLetters.G14.tag()))
        assertNull(NineKeyLetters.filedAs("ab,cd"))
        // 双拼's nine keys' tag under qk: is a layout of those groups, not NINE_SP
        val nineSp = NineKeyLetters.filedAs(NineKeyLetters.NINE_SP.tag())
        assertNotNull(nineSp)
        assertTrue(nineSp != NineKeyLetters.NINE_SP && nineSp!!.codes[0].code in NineKeyLetters.CUSTOM_BLOCK)
        // asked again: the same
        assertSame(nineSp, NineKeyLetters.filedAs(NineKeyLetters.NINE_SP.tag()))
    }

    @Test
    fun groupsMissingALetterOrTooLongOrTooManyAreRefused() {
        val missing = k17.filter { it != "m" }
        assertNotNull(NineKeyLetters.problem(missing))
        assertTrue(NineKeyLetters.problem(missing)!!.contains("m"))
        assertThrows(IllegalArgumentException::class.java) { NineKeyLetters.custom(missing) }
        assertNotNull(NineKeyLetters.problem(k17 + "abcdef"))
        assertNotNull(NineKeyLetters.problem(k17 + "aa"))
        assertNotNull(NineKeyLetters.problem(k17 + "a1"))
        assertNotNull(NineKeyLetters.problem(k17 + ""))
        assertNotNull(NineKeyLetters.problem(k17 + List(NineKeyLetters.CUSTOM_MAX - k17.size + 1) { "a" }))
        assertNull(NineKeyLetters.problem(k17 + List(NineKeyLetters.CUSTOM_MAX - k17.size) { "a" }))
        assertNotNull(NineKeyLetters.problem(emptyList()))
        assertNull(NineKeyLetters.problem(k17))
    }
}
