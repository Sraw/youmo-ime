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
