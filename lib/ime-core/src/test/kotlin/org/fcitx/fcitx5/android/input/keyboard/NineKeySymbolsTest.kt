/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.pinyin.nineSp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NineKeySymbolsTest {

    @Test
    fun unsetEveryKeyHasItsDefaults() {
        val symbols = NineKeySymbols.parse("")
        assertTrue(symbols.isStandard)
        assertEquals(listOf("@", "!", "\""), symbols.symbols('2'))
        assertEquals("…", symbols.symbols('1').first())
        assertEquals(emptyList<String>(), symbols.symbols('0'))
    }

    @Test
    fun aKeySetOtherwiseIsKeptAloneAndReadBack() {
        val symbols = NineKeySymbols.STANDARD.with('5', listOf("【", "】", "^_^")).with('7', emptyList())
        assertEquals("5 【 】 ^_^\n7", symbols.serialize())
        val back = NineKeySymbols.parse(symbols.serialize())
        assertEquals(symbols, back)
        assertEquals(listOf("【", "】", "^_^"), back.symbols('5'))
        assertEquals(emptyList<String>(), back.symbols('7'))
        assertTrue(back.isDefault('2'))
        assertFalse(back.isDefault('7'))
    }

    @Test
    fun setBackToItsDefaultsAKeyIsNoChange() {
        val symbols = NineKeySymbols.STANDARD.with('2', listOf("@")).with('2', listOf("@", "!", "\""))
        assertTrue(symbols.isStandard)
        assertEquals("", symbols.serialize())
    }

    @Test
    fun digitsLettersAndRepeatsAreNotKeptAndTenAtMost() {
        assertEquals(listOf("!", "ab", "。"), NineKeySymbols.split(" ! 2 a A ab  ! 。 "))
        assertEquals(NineKeySymbols.MAX, NineKeySymbols.split((1..20).joinToString(" ") { "#$it" }).size)
        assertEquals(listOf("?"), NineKeySymbols.STANDARD.with('8', listOf("?", "8", "u")).symbols('8'))
    }

    @Test
    fun typedOnlyWhatIsRefusedTheKeyIsLeftAsItWas() {
        assertEquals(null, NineKeySymbols.edited("a"))
        assertEquals(null, NineKeySymbols.edited("1 2"))
        assertEquals(emptyList<String>(), NineKeySymbols.edited(""))
        assertEquals(emptyList<String>(), NineKeySymbols.edited("  "))
        assertEquals(listOf("，"), NineKeySymbols.edited("a ，"))
    }

    // 九键双拼's abc code is a key, never a symbol
    @Test
    fun aKeyboardsCodeIsNoSymbol() {
        assertFalse(NineKeySymbols.allowed(nineSp("2")))
        assertEquals(listOf("@"), NineKeySymbols.split("${nineSp("2")} @"))
        // the code of an earlier build is text now
        assertTrue(NineKeySymbols.allowed("⒉"))
    }

    @Test
    fun aFullWidthSpaceSeparatesToo() {
        assertEquals(listOf("【", "】", "·"), NineKeySymbols.split("【\u3000】\u00A0·"))
    }

    @Test
    fun whatIsReadBackIsKeptAsWhatIsTyped() {
        val line = "2 7 z " + (1..12).joinToString(" ") { "#$it" }
        val read = NineKeySymbols.parse(line).symbols('2')
        assertEquals((1..NineKeySymbols.MAX).map { "#$it" }, read)
    }

    @Test
    fun aLineNotAKeysIsLeftOut() {
        assertEquals(NineKeySymbols.STANDARD.with('3', listOf("%")), NineKeySymbols.parse("0 !\nx ?\n\n3 %\n12 #"))
    }
}
