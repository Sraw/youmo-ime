/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NineKeyLettersTest {

    private val standard = NineKeyLetters.STANDARD

    @Test
    fun asOnAPhoneEachLetterIsOnOneKey() {
        assertEquals(('a'..'z').joinToString(""), ('2'..'9').joinToString("") { standard.letters(it) })
        for (d in '2'..'9') standard.letters(d).forEach { assertEquals(d.toString(), standard.keysOf(it)) }
        assertEquals(listOf("64426"), standard.typings("nihao"))
        assertEquals(listOf("58"), standard.typings("lv"))
        assertEquals("", standard.missing)
        assertEquals("", standard.letters('1'))
        assertEquals('9', standard.key('z'))
        // what no key has stands for itself: a digit, 1, an apostrophe
        assertEquals("591'", "591'".map(standard::key).joinToString(""))
    }

    @Test
    fun aLetterOnTwoKeysIsTypedFromEither() {
        val keys = NineKeyLetters.of(listOf("abc", "def", "ghi", "jka", "mno", "pqrs", "tuv", "wxyz"))
        assertEquals("25", keys.keysOf('a'))
        assertEquals('2', keys.key('a'))
        assertEquals('5', keys.key('5'))
        assertEquals(listOf("22", "25"), keys.typings("ba"))
        assertTrue(keys.sameKey('5', 'a'))
        assertTrue(keys.sameKey('A', 'j'))
        assertFalse(keys.sameKey('B', 'j'))
        // l is on no key: nothing with it is typed
        assertEquals("l", keys.missing)
        assertEquals(emptyList<String>(), keys.typings("lai"))
    }

    @Test
    fun aKeyHasAtMostFiveLettersEachOnce() {
        assertEquals("abcde", NineKeyLetters.normalize("aAbB1cdefg"))
        val keys = NineKeyLetters.of(listOf("abcdefg"))
        assertEquals("abcde", keys.letters('2'))
        // the keys not given as on a phone
        assertEquals("def", keys.letters('3'))
    }

    @Test
    fun keptAsTextAndReadBack() {
        assertEquals("", standard.serialize())
        assertEquals(standard, NineKeyLetters.parse(""))
        assertEquals(standard, NineKeyLetters.parse("abc,def"))
        val keys = NineKeyLetters.of(listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyzl"))
        assertEquals(keys, NineKeyLetters.parse(keys.serialize()))
        assertFalse(keys.isStandard)
        assertTrue(NineKeyLetters.of(listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz")).isStandard)
    }
}
