/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class T9SegmenterTest {

    private val segmenter = T9Segmenter()

    /** The syllables of the edges from [at] to [to] of [kind], spelt. */
    private fun SyllableGraph.read(at: Int, to: Int, kind: Kind = Kind.SYLLABLE): Set<String> =
        edges(at).filter { to(it) == to && kind(it) == kind }
            .flatMap { e -> (0 until matches(e).size).map { Syllables.spelling(matches(e).syllable(it)) } }.toSet()

    @Test
    fun digitsAreEverySyllableTheyType() {
        assertEquals("64426", T9Segmenter.digits("nihao"))
        assertEquals("58", T9Segmenter.digits("lv"))
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
    }

    @Test
    fun aSyllableOfOneLetterKeepsNoInitialOffItsDigitNorReadsWhatItStartsAsExtended() {
        val strict = T9Segmenter(abbreviations = false)
        // 2 is 啊 and 6 哦 too: b and n stand for their syllables all the same, 北京 and 南京
        assertTrue("bei" in strict.segment("25").read(0, 1, Kind.INITIAL))
        assertTrue("nan" in strict.segment("65").read(0, 1, Kind.INITIAL))
        assertTrue("a" in strict.segment("25").read(0, 1))
        // typed last, the start of a syllable still being typed, not a whole one read on
        val six = segmenter.segment("6")
        assertTrue(setOf("men", "ni", "ou").all { it in six.read(0, 1, Kind.PARTIAL) })
        assertTrue(six.read(0, 1, Kind.EXTENDED).isEmpty())
        assertTrue("ba" in segmenter.segment("2").read(0, 1, Kind.PARTIAL))
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
}
