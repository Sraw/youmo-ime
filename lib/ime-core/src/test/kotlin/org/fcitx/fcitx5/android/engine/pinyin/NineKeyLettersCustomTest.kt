/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.scene.layout.Groups
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Any layout's letter groups as a grouped keyboard ([NineKeyLetters.custom]) and a tap's place on a key's letters in two dimensions. */
class NineKeyLettersCustomTest {

    // the keyboard in use is process-wide (NineKeyLetters.inUse): none of this test's left for the next
    @After
    fun noneInUse() {
        NineKeyLetters.clearInUse()
    }

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
                val sent = Math.round(x * 16) / 16f
                assertEquals(NineKeyLetters.G14.against(index, sent), NineKeyLetters.G14.against(code, index, NineKeyLetters.place(sent, 0.5f)), 0f)
            }
        }
    }

    @Test
    fun aLetterGoesAgainstATapByItsDistanceOverTheFarthestCorners() {
        val square = listOf("pq", "rs")
        assertEquals(0f, NineKeyLetters.against(0, 0.2f, 0.2f, square), 0f)
        assertEquals(1f, NineKeyLetters.against(0, 1f, 1f, square), 1e-6f)
        // r is below p: straight down to the bottom edge, half its farthest corner's distance (0.5 over √0.5)
        assertEquals(0.5f / kotlin.math.sqrt(0.5f), NineKeyLetters.against(0, 0.25f, 1f, square), 1e-6f)
        assertEquals(0f, NineKeyLetters.against(2, 0.25f, 1f, square), 0f)
        val three = listOf("abc")
        assertEquals(0f, NineKeyLetters.against(1, 0.5f, 0.5f, three), 0f)
        assertEquals(0.75f, NineKeyLetters.against(0, 2 / 3f + 1 / 6f, 0.5f, three), 1e-6f)
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
        assertEquals(0.75f, NineKeyLetters.placeX(0.75f), 0f)
        assertTrue(NineKeyLetters.placeY(0.75f).isNaN())
    }

    @Test
    fun groupsOfABuiltInKeyboardAreThatKeyboardAndItsRecord() {
        assertSame(NineKeyLetters.G14, NineKeyLetters.custom(NineKeyLetters.G14.tag().split(',')))
        assertSame(NineKeyLetters.G18, NineKeyLetters.custom(NineKeyLetters.G18.tag().split(',')))
        val rows = NineKeyLetters.G14.tag().split(',').map { it.toList().joinToString(" ") }
        // written in rows, the same letters: the same keyboard (codes, record), its rows kept so a tap across alone leans to no stacked letter
        val stacked = NineKeyLetters.custom(rows)
        assertEquals(NineKeyLetters.G14, stacked)
        assertEquals(NineKeyLetters.G14.tag(), stacked.tag())
        assertEquals(listOf("q", "w"), stacked.rowsOf(stacked.codes[0]))
    }

    @Test
    @Uat("PY-154")
    fun theBuiltInGroupsInAnotherOrderAreThatKeyboardAndItsRecord() {
        // the 18 keys with the first two keys swapped, the 14 keys backwards
        val g18 = NineKeyLetters.G18.tag().split(',')
        val swapped = listOf(g18[1], g18[0]) + g18.drop(2)
        assertSame(NineKeyLetters.G18, NineKeyLetters.custom(swapped))
        assertSame(NineKeyLetters.G14, NineKeyLetters.custom(NineKeyLetters.G14.tag().split(',').reversed()))
        // its codes are its groups' (a group's first letter's), whichever key they are on
        val made = NineKeyLetters.custom(swapped)
        assertEquals(NineKeyLetters.G18.codes, made.codes)
        assertEquals(NineKeyLetters.G18.tag(), made.tag())
        assertEquals(NineKeyLetters.G18.key('w'), made.key('w'))
        // in rows: each group's rows go with it to the built-in keyboard's order
        val rows = NineKeyLetters.G14.tag().split(',').map { it.toList().joinToString(" ") }.reversed()
        val stacked = NineKeyLetters.custom(rows)
        assertEquals(NineKeyLetters.G14, stacked)
        assertEquals(listOf("q", "w"), stacked.rowsOf(NineKeyLetters.G14.key('q')))
        assertEquals(listOf("b", "n"), stacked.rowsOf(NineKeyLetters.G14.key('n')))
        // a group twice (another left out by a key of its own letters): a keyboard of its own, not the built-in one
        val twice = g18.dropLast(1) + g18[0]
        assertTrue(NineKeyLetters.custom(twice).codes.all { it.code in NineKeyLetters.CUSTOM_BLOCK })
        val moved = listOf(g18[0] + g18[1].first(), g18[1].drop(1)) + g18.drop(2)
        assertTrue(NineKeyLetters.custom(moved).codes[0].code in NineKeyLetters.CUSTOM_BLOCK)
    }

    // QW written WQ is no 14 keys: W drawn left would lean to Q
    @Test
    fun aGroupsLettersInAnotherOrderAreNoBuiltInGroup() {
        val g14 = NineKeyLetters.G14.tag().split(',')
        val wq = listOf("wq") + g14.drop(1)
        val made = NineKeyLetters.custom(wq)
        assertTrue(made != NineKeyLetters.G14)
        assertTrue(made.codes.all { it.code in NineKeyLetters.CUSTOM_BLOCK })
        assertEquals("wq", made.letters(made.codes[0]))
        assertEquals(0f, made.against(made.codes[0], 0, 0.2f))
        assertTrue(made.against(made.codes[0], 1, 0.2f) > 0f)
    }

    // the groups told the engine make a keyboard of their own only where they are no built-in keyboard's
    @Test
    fun aKeyboardOfItsOwnIsNoneForTheBuiltInGroups() {
        val g18 = NineKeyLetters.G18.tag().split(',')
        assertNull(NineKeyLetters.ownOf(g18))
        assertNull(NineKeyLetters.ownOf(g18.reversed()))
        assertNull(NineKeyLetters.ownOf(NineKeyLetters.G14.tag().split(',')))
        // a letter on no key: still its own keyboard, the letter typed around
        assertEquals(NineKeyLetters.custom(k17.filter { it != "m" }), NineKeyLetters.ownOf(k17.filter { it != "m" }))
        assertEquals(NineKeyLetters.custom(k17), NineKeyLetters.ownOf(k17))
    }

    @Test
    fun aRecordUnderTheBuiltInGroupsInAnotherOrderIsStillOneOfItsOwn() {
        // older builds typed such a layout on a keyboard of its own: its learning was filed with those codes
        val tag = NineKeyLetters.G18.tag().split(',').reversed().joinToString(",")
        val filed = NineKeyLetters.filedAs(tag)
        assertNotNull(filed)
        assertEquals(tag, filed!!.tag())
        assertTrue(filed.codes.all { it.code in NineKeyLetters.CUSTOM_BLOCK })
        assertEquals("m", filed.letters(filed.codes[0]))
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
        assertEquals(2, made.keysOf('p').length)
        // a code read back: the keyboard in use, which making one does not set
        val other = NineKeyLetters.custom(k17.reversed())
        NineKeyLetters.inUse(made)
        assertSame(made, NineKeyLetters.ofCode(made.codes[0].code))
        NineKeyLetters.custom(k17.reversed())
        assertSame(made, NineKeyLetters.ofCode(made.codes[0].code))
        NineKeyLetters.inUse(other)
        assertSame(other, NineKeyLetters.ofCode(made.codes[0].code))
        val small = NineKeyLetters.custom(listOf("abcde", "fghij", "klmno", "pqrst", "uvwxy", "z"))
        NineKeyLetters.inUse(small)
        assertNull(NineKeyLetters.ofCode(made.codes[6].code))
        // none in use (what a test leaves after it): read as no keyboard's, a built-in one's still read
        NineKeyLetters.clearInUse()
        assertNull(NineKeyLetters.ofCode(made.codes[0].code))
        assertSame(NineKeyLetters.G14, NineKeyLetters.ofCode(NineKeyLetters.G14.codes[0].code))
        NineKeyLetters.inUse(made)
        assertEquals(made, NineKeyLetters.custom(k17.map { it.replace(" ", "") }))
        assertEquals(made.tag(), NineKeyLetters.filedAs(made.tag())?.tag())
        assertEquals(made.codes, NineKeyLetters.filedAs(made.tag())?.codes)
        // reading a record does not change which keyboard a code is read as
        assertSame(made, NineKeyLetters.ofCode(made.codes[0].code))
        assertSame(NineKeyLetters.G14, NineKeyLetters.filedAs(NineKeyLetters.G14.tag()))
        // a keyboard lacking letters files its words too; groups of no shape are none
        assertEquals("ab,cd", NineKeyLetters.filedAs("ab,cd")?.tag())
        assertNull(NineKeyLetters.filedAs("ab,c1"))
        // a record of many tags (damaged, say) keeps no keyboard of each: past the few kept, read anew alike
        repeat(100) { NineKeyLetters.filedAs("bad$it") }
        assertEquals(made.codes, NineKeyLetters.filedAs(made.tag())?.codes)
        // 双拼's nine keys' tag under qk: is a layout of those groups, not NINE_SP
        val nineSp = NineKeyLetters.filedAs(NineKeyLetters.NINE_SP.tag())
        assertNotNull(nineSp)
        assertTrue(nineSp != NineKeyLetters.NINE_SP && nineSp!!.codes[0].code in NineKeyLetters.CUSTOM_BLOCK)
        assertSame(nineSp, NineKeyLetters.filedAs(NineKeyLetters.NINE_SP.tag()))
    }

    @Test
    fun groupsTooLongOrTooManyAreRefusedALetterMissingIsNot() {
        val missing = k17.filter { it != "m" }
        assertNull(NineKeyLetters.problem(missing))
        val made = NineKeyLetters.custom(missing)
        assertEquals("m", Groups.missing(missing))
        // the missing letter stands for itself: no key's code
        assertEquals('m', made.key('m'))
        assertEquals("", made.keysOf('m'))
        assertThrows(IllegalArgumentException::class.java) { NineKeyLetters.custom(k17 + "aa") }
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
