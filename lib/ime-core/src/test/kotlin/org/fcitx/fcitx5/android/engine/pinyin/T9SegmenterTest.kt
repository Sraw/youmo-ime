/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class T9SegmenterTest {

    // as the keyboard builds it; ime-eval's t9 scheme reads 简拼 with abbreviations too
    private val segmenter = T9Segmenter(abbreviations = false)

    /** The syllables of the edges from [at] to [to] of [kind], spelt. */
    private fun SyllableGraph.read(at: Int, to: Int, kind: Kind = Kind.SYLLABLE): Set<String> =
        edges(at).filter { to(it) == to && kind(it) == kind }
            .flatMap { e -> (0 until matches(e).size).map { Syllables.spelling(matches(e).syllable(it)) } }.toSet()

    /** As [read], each marked with its flags as [SyllableMatches] prints them: `zhong~`. */
    private fun SyllableGraph.marked(at: Int, to: Int, kind: Kind = Kind.SYLLABLE): Set<String> =
        edges(at).filter { to(it) == to && kind(it) == kind }.flatMap { matches(it).toString().split(' ') }.toSet()

    @Test
    fun digitsAreEverySyllableTheyType() {
        val g = segmenter.segment("64426")
        assertTrue(setOf("ni", "mi").all { it in g.read(0, 2) })
        assertTrue("hao" in g.read(2, 5))
        assertTrue("o" in g.read(0, 1))
        // the last digits on their way to a longer syllable
        assertTrue(setOf("nian", "ming").all { it in segmenter.segment("64").read(0, 2, Kind.EXTENDED) })
    }

    @Test
    fun anInitialAloneOnlyWhereNothingElseIsReadUnlessAbbreviationsAre() {
        // 44: no syllable is g or h then g, h or i, so the 4 is an initial
        val strict = T9Segmenter(abbreviations = false).segment("4426")
        assertTrue(setOf("ga", "ha").all { it in strict.read(0, 1, Kind.INITIAL) })
        // 呣 and 嗯 (6) and 儿 (7) keep no initial off: 79 is q, s … then w, x …
        assertTrue("qu" in T9Segmenter(abbreviations = false).segment("7948").read(0, 1, Kind.INITIAL))
        // 64 reads ni: no initial n there
        assertTrue(T9Segmenter(abbreviations = false).segment("6426").read(0, 1, Kind.INITIAL).isEmpty())
        // with them, beside a syllable no longer than the initial too: 74 is si, and sh in 745 时间
        val abbreviating = T9Segmenter()
        assertTrue("si" in abbreviating.segment("745").read(0, 2))
        assertTrue("shi" in abbreviating.segment("745").read(0, 2, Kind.INITIAL))
        assertTrue(segmenter.segment("745").read(0, 2, Kind.INITIAL).isEmpty())
        // but not beside a longer one (744 is shi, 6426 nian), nor at the end, where its start is read
        assertTrue(abbreviating.segment("7448").read(0, 2, Kind.INITIAL).isEmpty())
        assertTrue(abbreviating.segment("6426").read(0, 1, Kind.INITIAL).isEmpty())
        assertTrue(abbreviating.segment("74").read(0, 2, Kind.INITIAL).isEmpty())
    }

    @Test
    fun aFuzzyPairReadsTheKeysOfTheOtherSpelling() {
        val fuzzy = T9Segmenter(setOf(Fuzzy.Z_ZH, Fuzzy.L_N, Fuzzy.IN_ING), abbreviations = false)
        // z is 9 and zh 94: each reads the other's syllables, as an initial too
        assertTrue("zhong~" in fuzzy.segment("9664").marked(0, 4))
        assertTrue("zong~" in fuzzy.segment("94664").marked(0, 5))
        assertTrue("zhong~…" in fuzzy.segment("95").marked(0, 1, Kind.INITIAL))
        // l is 5 and n 6
        assertTrue("nan~" in fuzzy.segment("526").marked(0, 3))
        assertTrue("lan~…" in fuzzy.segment("65").marked(0, 1, Kind.INITIAL))
        // and a final pair: 546 is jing too
        assertTrue("jing~" in fuzzy.segment("546").marked(0, 3))
        // none of it without the pairs
        assertFalse("zhong" in segmenter.segment("9664").read(0, 4))
        assertFalse("lan" in segmenter.segment("65").read(0, 1, Kind.INITIAL))
    }

    @Test
    fun aSyllableOfOneLetterKeepsNoInitialOffItsDigitAndReadsWhatItStartsAsExtended() {
        val strict = T9Segmenter(abbreviations = false)
        // 2 is 啊 and 6 哦 too: b and n stand for their syllables all the same, 北京 and 南京
        assertTrue("bei" in strict.segment("25").read(0, 1, Kind.INITIAL))
        assertTrue("nan" in strict.segment("65").read(0, 1, Kind.INITIAL))
        assertTrue("a" in strict.segment("25").read(0, 1))
        // typed last, a syllable read on from a whole one, as on the full keyboard: a lone 2 is
        // 啊 before 不, whose b only starts it (it read b once, 不 first)
        val six = segmenter.segment("6")
        assertTrue(setOf("men", "ni", "ou").all { it in six.read(0, 1, Kind.EXTENDED) })
        assertTrue(six.read(0, 1, Kind.PARTIAL).isEmpty())
        val two = segmenter.segment("2")
        assertTrue("a" in two.read(0, 1))
        assertTrue("ba" in two.read(0, 1, Kind.EXTENDED))
        // with no whole syllable, only started
        assertTrue("ta" in segmenter.segment("8").read(0, 1, Kind.PARTIAL))
    }

    @Test
    fun uAloneIsNoSpellingOfOuUnderTheFuzzyPair() {
        val ou = T9Segmenter(setOf(Fuzzy.U_OU), abbreviations = false)
        // 8 types no syllable, so its t still stands for its syllables: 86 他们
        val g = ou.segment("86")
        assertTrue(g.read(0, 1).isEmpty())
        assertTrue("ta" in g.read(0, 1, Kind.INITIAL))
        // after an initial the pair holds: 68 is mou too
        assertTrue(setOf("mu", "mou").all { it in ou.segment("68").read(0, 2) })
    }

    @Test
    fun aLetterTypedAsItselfKeepsTheSyllablesWithItThere() {
        // 7 then 4 4 fast: s, p, q or r, then h
        val sh = segmenter.segment("7H")
        assertTrue(sh.read(0, 2).isEmpty())
        val starts = sh.read(0, 2, Kind.EXTENDED) + sh.read(0, 2, Kind.PARTIAL)
        assertTrue(setOf("shi", "sha", "shang").all { it in starts })
        assertTrue(starts.none { it[1] != 'h' })
        // the digit before it still any of its letters, the one after too
        assertEquals(setOf("ni", "ng"), segmenter.segment("N4").read(0, 2))
        assertEquals(setOf("ni", "mi"), segmenter.segment("6I").read(0, 2))
        assertTrue("hao" in segmenter.segment("6IH26").read(2, 5))
        assertTrue(segmenter.segment("6IG26").read(2, 5).none { it == "hao" })
        // at the end with no syllable whole left, the starts are only on their way (PARTIAL)
        assertTrue(sh.read(0, 2, Kind.EXTENDED).isEmpty())
        // üe typed lue as well as lve: 略
        assertTrue("lve" in segmenter.segment("5U3").read(0, 3))
        assertTrue("lve" in segmenter.segment("5V3").read(0, 3))
        // a letter no syllable has there leaves nothing to read
        assertTrue(segmenter.segment("6K").read(0, 2).isEmpty())
        assertTrue(T9Segmenter.isKey('H') && T9Segmenter.isKey('2') && !T9Segmenter.isKey('h') && !T9Segmenter.isKey('1'))
    }

    @Test
    fun aSyllableTakenIsReadAsItselfAlone() {
        val g = segmenter.segment("ni'426")
        assertEquals(setOf("ni"), g.read(0, 3))
        assertTrue("hao" in g.read(3, 6))
        // a letter taken is an initial, m too, which is 呣 alone
        assertTrue("hao" in segmenter.segment("h'26").read(0, 2, Kind.INITIAL))
        assertTrue("ma" in segmenter.segment("m'2").read(0, 2, Kind.INITIAL))
        assertTrue(segmenter.segment("m'2").read(0, 2).isEmpty())
        assertFalse(segmenter.reads('a'))
        assertFalse(segmenter.typesLetters)
    }

    @Test
    fun lettersTheUserPutOnTheKeysAreReadFromEach() {
        // a on 2 and on 5 too, l moved from 5 to 9
        val keys = NineKeyLetters.of(listOf("abc", "def", "ghi", "jka", "mno", "pqrs", "tuv", "wxyzl"))
        val custom = T9Segmenter(abbreviations = false, letters = keys)
        assertTrue("ba" in custom.segment("22").read(0, 2))
        assertTrue("ba" in custom.segment("25").read(0, 2))
        assertTrue("lai" in custom.segment("924").read(0, 3))
        // l is no longer on 5
        assertFalse("lai" in custom.segment("524").read(0, 3))
        assertTrue("jia" in custom.segment("542").read(0, 3))
        // a letter typed as itself is that letter whichever key typed it
        val typed = custom.segment("2A").read(0, 2)
        assertTrue("ba" in typed)
        assertFalse("bo" in typed)
    }

    @Test
    fun ueIsUeOnTheUsersKeysTooAndAKeyOfNoLettersIsNotRead() {
        // v moved off u's key to 9: 略 typed lue is 5 8 3 still, and lve 5 9 3
        val keys = NineKeyLetters.of(listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tu", "vwxyz"))
        val custom = T9Segmenter(abbreviations = false, letters = keys)
        assertTrue("lve" in custom.segment("583").read(0, 3))
        assertTrue("lve" in custom.segment("593").read(0, 3))
        val empty = T9Segmenter(letters = NineKeyLetters.of(listOf("abc", "")))
        assertFalse(empty.reads('3'))
        assertTrue(empty.reads('2'))
    }

    /** 八点开会 at a pace of 0.4 s a key came out 备案拉回: a run read only as the letter it came to (B). */
    @Test
    fun aRunOfAKeyPressedAgainReadsAsKeysAndAsTheLetterItCameTo() {
        val a2 = T9Segmenter.again('2')
        val a4 = T9Segmenter.again('4')
        // 7 then 4 4 fast: shi as keys, or s and h on the way to sha, shang
        val shi = segmenter.segment("74$a4")
        assertTrue("shi" in shi.read(0, 3))
        assertTrue("shang" in shi.read(0, 3, Kind.PARTIAL) + shi.read(0, 3, Kind.EXTENDED))
        // 2 four times fast: baba as keys, a run going on past a syllable read as keys alone
        val baba = segmenter.segment("2$a2$a2$a2")
        assertTrue("ba" in baba.read(0, 2) && "ba" in baba.read(2, 4))
        // or the letter it came to, round to a again: 啊 whole
        assertTrue("a" in baba.read(0, 4))
        assertEquals(2, segmenter.lettersOf("74$a4", Syllables.id("shang")))
        assertEquals(3, segmenter.lettersOf("74$a4", Syllables.id("shi")))
        assertEquals("744", T9Segmenter.plain("74$a4"))
        assertTrue(T9Segmenter.isKey(a4) && T9Segmenter.digitOf(a4) == '4')
    }

    /** The B of 2 2 fast went on as EXTENDED, its starts paying for the 22 that is ba, unlike B typed as itself. */
    @Test
    fun eachReadingOfARunStartsItsSyllablesAsItself() {
        val a2 = T9Segmenter.again('2')
        val a4 = T9Segmenter.again('4')
        // 22 is ba, so what it starts is read on from a syllable; B is none, so what it starts is only started
        val b = segmenter.segment("2$a2")
        assertTrue("ban…" in b.marked(0, 2, Kind.EXTENDED))
        assertTrue("bu…*" in b.marked(0, 2, Kind.PARTIAL))
        assertFalse("bu" in b.read(0, 2, Kind.EXTENDED))
        // 744 is shi and starts none: sh of 7 then 4 4 fast only starts sha, shang
        val shi = segmenter.segment("74$a4")
        assertTrue("shang" in shi.read(0, 3, Kind.PARTIAL))
        assertTrue(shi.read(0, 3, Kind.EXTENDED).isEmpty())
    }

    /** 4 pressed again then 26 lost h'hao and g'hao (还好, 很好, 刚好), which 4426 typed slowly reads. */
    @Test
    fun aRunReadAsItsLetterKeepsNoInitialOffTheKeys() {
        val fast = segmenter.segment("4${T9Segmenter.again('4')}26")
        assertTrue(setOf("ga", "ha").all { it in fast.read(0, 1, Kind.INITIAL) })
        assertEquals(segmenter.segment("4426").read(0, 1, Kind.INITIAL), fast.read(0, 1, Kind.INITIAL))
        // the run is still read as its letter: H then 26, hao
        assertTrue("hao" in fast.read(0, 4))
    }

    @Test
    fun twoRunsInOneInputReadEachAsItsLetterWhereTheOtherIsOne() {
        val a3 = T9Segmenter.again('3')
        val a4 = T9Segmenter.again('4')
        // 4 4 then 3 3 fast: H then E, the second run found after the first is one letter
        val he = "4${a4}3$a3"
        assertTrue("he*" in segmenter.segment(he).marked(0, 4))
        assertEquals(2, segmenter.lettersOf(he, Syllables.id("he")))
        // a run not at the start: 7, then 4 4 and 3 3 fast
        assertTrue("he*" in segmenter.segment("74${a4}3$a3").marked(1, 5))
    }

    /**
     * 12 presses was all a syllable could take, as on a phone's keys: one of 13 on the user's was read as keys alone.
     * Each letter counted at its nearest key, one typed from a farther key or going round a key was read so too,
     * and with one round of a key for the whole syllable, one with two letters gone round.
     */
    @Test
    fun aSyllableTypedALetterAtATimeTakesAsManyPressesAsTheUsersKeysNeed() {
        // z fifth on 9: zhuang letter by letter is 13 presses
        val keys = NineKeyLetters.of(listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "lwxyz"))
        fun pressed(digit: Char, times: Int) = digit.toString() + T9Segmenter.again(digit).toString().repeat(times - 1)
        val zhuang = pressed('9', 5) + pressed('4', 2) + pressed('8', 2) + "2" + pressed('6', 2) + "4"
        assertEquals(13, zhuang.length)
        assertTrue("zhuang" in T9Segmenter(abbreviations = false, letters = keys).segment(zhuang).read(0, 13))
        // s fifth on 9 and fourth on 7: shuang with s from 9 is 13 presses, from 7 12
        val farther = NineKeyLetters.of(listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyzs"))
        val shuang = pressed('9', 5) + pressed('4', 2) + pressed('8', 2) + "2" + pressed('6', 2) + "4"
        assertTrue("shuang" in T9Segmenter(abbreviations = false, letters = farther).segment(shuang).read(0, 13))
        // a as 2 four times, round to a again: shuang in 15 presses on a phone's keys
        val round = pressed('7', 4) + pressed('4', 2) + pressed('8', 2) + pressed('2', 4) + pressed('6', 2) + "4"
        assertEquals(15, round.length)
        assertTrue("shuang" in segmenter.segment(round).read(0, 15))
        // a as 2 four times and n as 6 five times: 18 presses
        val twice = pressed('7', 4) + pressed('4', 2) + pressed('8', 2) + pressed('2', 4) + pressed('6', 5) + "4"
        assertEquals(18, twice.length)
        assertTrue("shuang" in segmenter.segment(twice).read(0, 18))
        // every letter of zhuang gone round its key once more: the most on a phone's keys, 31
        val everyLetter = pressed('9', 8) + pressed('4', 5) + pressed('8', 5) + pressed('2', 4) + pressed('6', 5) + pressed('4', 4)
        assertEquals(31, everyLetter.length)
        assertTrue("zhuang" in segmenter.segment(everyLetter).read(0, 31))
    }

    @Test
    fun aKeyPressedAgainIsShownAsItsDigitAndStandsForNoSyllableOfTheKeyboard() {
        val a4 = T9Segmenter.again('4')
        assertEquals("744h", T9Segmenter.asTyped("74${a4}H"))
        // the private use area is the keyboard's syllables
        assertTrue(T9Segmenter.AGAIN.none { it in Keyboard.SYLLABLES })
    }
}
