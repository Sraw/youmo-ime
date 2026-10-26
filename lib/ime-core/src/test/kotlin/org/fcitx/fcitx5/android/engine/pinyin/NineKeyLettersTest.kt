/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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

    @Test
    fun theFourteenKeysTypeTheirGroupsByCodesNoOtherKeyTypes() {
        val g14 = NineKeyLetters.G14
        assertEquals(g14("qw er ty ui op as df gh jk l zx cv bn m"), g14.codes)
        // contract: each code is U+F8DA plus its key's first letter, private use past the
        // syllables', no case, no digit, no letter, none twice
        g14.codes.forEach { c ->
            assertEquals('\uF8DA' + (g14.letters(c)[0] - 'a'), c)
            assertEquals(Character.PRIVATE_USE.toInt(), Character.getType(c))
            assertFalse(c.isLetterOrDigit())
            assertEquals(-1, Character.digit(c, 36))
            assertEquals(c, c.lowercaseChar())
            assertEquals(c, c.uppercaseChar())
            assertFalse(c in Keyboard.SYLLABLES)
        }
        assertEquals(14, g14.codes.toSet().size)
        assertEquals(g14Code('b'), g14.key('n'))
        assertEquals(g14("cv"), g14.keysOf('v'))
        assertEquals(listOf(g14("l cv")), g14.typings("lv"))
        assertEquals("bn ' ui", g14.shown("${g14("bn")}'${g14("ui")}"))
        // found again from its tag, as a qk: kind keeps it; parse knows the eight keys alone
        assertSame(g14, NineKeyLetters.grouped(g14.tag()))
        assertNull(NineKeyLetters.grouped(standard.tag()))
        assertEquals(standard, NineKeyLetters.parse(g14.tag()))
        assertThrows(IllegalStateException::class.java) { g14.serialize() }
        assertNull(g14.separator)
        assertEquals(listOf(g14("bn ui gh as op")), g14.typings("nihao"))
        assertEquals("qw,er,ty,ui,op,as,df,gh,jk,l,zx,cv,bn,m", g14.tag())
        // contract: the learning record files the 14 keys under this kind
        assertEquals("qk:qw,er,ty,ui,op,as,df,gh,jk,l,zx,cv,bn,m", Kinds.keys(g14.tag()))
        assertEquals("bn", g14.letters(g14Code('b')))
        assertEquals("", g14.letters('2'))
        assertTrue(g14.isCode(g14Code('b')))
        assertFalse(g14.isCode('2'))
        assertFalse(g14.isCode('b'))
        assertEquals("", g14.missing)
        assertTrue(g14.sameKey(g14Code('b'), 'n'))
        assertTrue(g14.sameKey('N', 'b'))
        // what cannot be read is shown and committed as its keys' first letters, never as a code
        assertEquals("ub", g14.asTyped(g14("ui bn")))
        assertEquals("ni'g", g14.asTyped("Ni'${g14("gh")}"))
        assertEquals("bn ui gh as op", g14.shown(g14("bn ui gh as op")))
        assertNotEquals(NineKeyLetters.STANDARD, g14)
    }

    @Test
    fun shuangpinsGroupedKeyboardsTypeTheirGroupsByCodesNoOtherKeyTypes() {
        val g18 = NineKeyLetters.G18
        val nine = NineKeyLetters.NINE_SP
        assertEquals("q,we,rt,y,u,io,p,a,sd,fg,h,jk,l;,z,xc,v,bn,m".split(',').joinToString("") { ('\uF8C0' + (it[0] - 'a')).toString() }, g18.codes)
        assertEquals("q,we,rt,y,u,io,p,a,sd,fg,h,jk,l;,z,xc,v,bn,m", g18.tag())
        assertEquals("abc,def,ghi,jkl;,mno,pqrs,tuv,wxyz", nine.tag())
        assertEquals(nineSp("23456789"), nine.codes)
        // contract: U+F8C0 plus the key's first letter, private use past the syllables', no case, no digit
        g18.codes.forEach { c ->
            assertEquals('\uF8C0' + (g18.letters(c)[0] - 'a'), c)
            assertEquals(Character.PRIVATE_USE.toInt(), Character.getType(c))
            assertEquals(-1, Character.digit(c, 36))
            assertEquals(c, c.lowercaseChar())
            assertEquals(c, c.uppercaseChar())
            assertFalse(c in Keyboard.SYLLABLES)
        }
        assertEquals(18, g18.codes.toSet().size)
        nine.codes.forEachIndexed { k, c ->
            // contract: U+F8F4 plus (the digit − 2), private use: nothing reads it as 2 to 9
            assertEquals('\uF8F4' + k, c)
            assertEquals(Character.PRIVATE_USE.toInt(), Character.getType(c))
            assertEquals(-1, Character.digit(c, 36))
            assertFalse(c in Keyboard.SYLLABLES)
        }
        assertEquals("", g18.missing)
        assertEquals("", nine.missing)
        assertEquals(listOf("\uF8C1\uF8C8"), g18.typings("ni"))
        assertEquals(listOf(nineSp("64")), nine.typings("ni"))
        assertEquals("bi", g18.asTyped("\uF8C1\uF8C8"))
        assertEquals("mg", nine.asTyped(nineSp("64")))
        assertEquals("bn io", g18.shown("\uF8C1\uF8C8"))
        for (keys in listOf(g18, nine)) {
            assertNull(keys.separator)
            assertFalse(keys.isStandard)
            assertSame(keys, NineKeyLetters.grouped(keys.tag()))
            assertThrows(IllegalStateException::class.java) { keys.serialize() }
        }
        // the nine keys' 双拼 tag is not 九键's: parse reads it as the phone's, no t9: row shared
        assertEquals(NineKeyLetters.STANDARD, NineKeyLetters.parse(nine.tag()))
        assertNotEquals(NineKeyLetters.STANDARD.tag(), nine.tag())
    }

    @Test
    fun aGroupedCodeIsOfOneKeyboardAlone() {
        val all = listOf(NineKeyLetters.G14, NineKeyLetters.G18, NineKeyLetters.NINE_SP)
        for (keys in all) keys.codes.forEach { c -> assertSame(keys, NineKeyLetters.ofCode(c.code)) }
        // no character is two keyboards' code; the codes of an earlier build (⒜–⒵, ⒉–⒐) are text now
        assertEquals(all.sumOf { it.codes.length }, all.joinToString("") { it.codes }.toSet().size)
        for (c in "23456789'a;⒈⒉⒐⒑⒜⒵\uF8BF\uF8FC\uF8FF") assertNull(c.toString(), NineKeyLetters.ofCode(c.code))
        assertNull(NineKeyLetters.ofCode(0xF0200))
    }

    @Test
    fun theGroupedCodesAreThreeBlocksOfThePrivateUseAreaPastTheSyllables() {
        val blocks = listOf(NineKeyLetters.G18_BLOCK, NineKeyLetters.G14_BLOCK, NineKeyLetters.NINE_SP_BLOCK)
        // one after the other, none overlapping, from past the syllables' to no further than the BMP's area
        assertEquals(Keyboard.SYLLABLES.last.code + 1, blocks.first().first)
        blocks.zipWithNext { a, b -> assertEquals(a.last + 1, b.first) }
        assertTrue(blocks.last().last <= 0xF8FF)
        assertEquals(blocks.first().first..blocks.last().last, NineKeyLetters.CODES)
        for (c in NineKeyLetters.CODES) assertEquals(Character.PRIVATE_USE.toInt(), Character.getType(c))
        for (keys in listOf(NineKeyLetters.G14, NineKeyLetters.G18, NineKeyLetters.NINE_SP)) {
            keys.codes.forEach { assertTrue(it.code in NineKeyLetters.CODES) }
        }
    }

    @Test
    fun semicolonIsTypedOnTheKeysHoldingIt() {
        // 微软's and 紫光's ing, on L: never normalized away
        assertEquals('\uF8CB', NineKeyLetters.G18.key(';'))
        assertEquals(nineSpCode('5'), NineKeyLetters.NINE_SP.key(';'))
        assertEquals("l;", NineKeyLetters.G18.letters('\uF8CB'))
        assertEquals("jkl;", NineKeyLetters.NINE_SP.letters(nineSpCode('5')))
        // no key holds it: itself, as before
        assertEquals(';', NineKeyLetters.G14.key(';'))
        assertEquals(';', NineKeyLetters.STANDARD.key(';'))
    }

    @Test
    fun theEightKeyLayoutsAreAsBefore() {
        // contract: what the nine keys learned and kept (t9:<tag>, the t9_letters preference) reads the same
        val custom = NineKeyLetters.parse("abc,def,ghi,jkl,mno,pqrs,tuv,wxyzl")
        for ((keys, tag) in listOf(standard to "abc,def,ghi,jkl,mno,pqrs,tuv,wxyz", custom to "abc,def,ghi,jkl,mno,pqrs,tuv,wxyzl")) {
            assertEquals("23456789", keys.codes)
            assertEquals('1', keys.separator)
            assertEquals(listOf("64426"), keys.typings("nihao"))
            assertEquals(tag, keys.tag())
            assertTrue(keys.isCode('2'))
            assertFalse(keys.isCode('1'))
            // digits stay digits: the nine keys' input is read, not spelled
            assertEquals("64426'1a", keys.asTyped("64426'1A"))
            assertEquals("mno ghi", keys.shown("64"))
        }
        assertEquals(listOf("95", "99"), custom.typings("wl"))
        assertEquals("", standard.serialize())
        assertEquals("abc,def,ghi,jkl,mno,pqrs,tuv,wxyzl", custom.serialize())
        assertEquals(custom, NineKeyLetters.parse(custom.serialize()))
    }
}
