/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.phrase.PhraseKeys
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class T9SegmenterZhuyinTest {

    private val dachen = T9Segmenter(abbreviations = false, letters = ZhuyinKeys.DACHEN)
    private val nine = T9Segmenter(abbreviations = false, letters = ZhuyinKeys.NINE)

    // 大千's input: each symbol its code, a tone after its syllable
    private fun dachen(typed: String) = typed.map { c -> ZhuyinKeys.MARKS.indexOf(c).let { if (it >= 0) ZhuyinKeys.toneCode(it + 1) else ZhuyinKeys.symbolCode(c) } }.joinToString("")

    // 注音九键's input: each symbol its group's code
    private fun nine(typed: String) = typed.map { c -> if (c == '\'') c else ZhuyinKeys.groupCode(ZhuyinKeys.GROUP_SYMBOLS.indexOfFirst { c in it }) }.joinToString("")

    private fun SyllableGraph.syllablesFrom(at: Int, kind: Kind = Kind.SYLLABLE) =
        edges(at).filter { kind(it) == kind }.flatMap { e -> (0 until matches(e).size).map { Syllables.spelling(matches(e).syllable(it)) } }.toSet()

    private fun SyllableGraph.edgeOf(at: Int, syllable: String) =
        edges(at).first { e -> (0 until matches(e).size).any { Syllables.spelling(matches(e).syllable(it)) == syllable } }

    @Test
    fun dachenReadsEachSyllableByItsSymbolsAndATone() {
        val graph = dachen.segment(dachen("ㄋㄧˇㄏㄠˇ"))
        // ㄋ alone is 嗯's n as well
        assertEquals(setOf("n", "ni"), graph.syllablesFrom(0))
        val ni = graph.edgeOf(0, "ni")
        assertEquals(3, graph.to(ni))
        assertEquals(3, graph.tone(ni))
        assertEquals(setOf("hao"), graph.syllablesFrom(3))
        assertEquals(3, graph.tone(graph.edgeOf(3, "hao")))
        assertEquals(dachen("ㄋㄧ"), graph.text(ni))
        // no tone typed: none
        val bare = dachen.segment(dachen("ㄓㄨㄥ"))
        assertEquals(0, bare.tone(bare.edgeOf(0, "zhong")))
        // ㄓ alone is zhi whole, and the start of zhong
        val zh = dachen.segment(dachen("ㄓ"))
        assertTrue(zh.syllablesFrom(0).contains("zhi"))
        assertTrue(zh.syllablesFrom(0, Kind.EXTENDED).contains("zhong"))
    }

    @Test
    fun theNineGroupsReadEverySyllableTheirSymbolsSpell() {
        val graph = nine.segment(nine("ㄋㄧㄏㄠ"))
        assertTrue(graph.syllablesFrom(0).contains("ni"))
        val ni = graph.edgeOf(0, "ni")
        assertTrue(graph.syllablesFrom(graph.to(ni)).contains("hao"))
        // ㄇㄋㄜ then ㄔㄘㄣㄧ: mi and ni alike
        assertTrue(graph.syllablesFrom(0).contains("mi"))
    }

    @Test
    fun aSymbolTypedAsItselfKeepsOnlyItsSyllables() {
        val typed = ZhuyinKeys.symbolCode('ㄋ').toString() + nine("ㄧ")
        val read = nine.segment(typed).syllablesFrom(0)
        assertTrue(read.contains("ni"))
        assertFalse(read.contains("mi"))
    }

    @Test
    fun theInputShowsAsItsSymbolsAndMarks() {
        assertEquals("ㄋㄧˇ", dachen.asTyped(dachen("ㄋㄧˇ")))
        // a group unread shows its first symbol; one typed as itself, itself
        assertEquals("ㄅㄋ", nine.asTyped(nine("ㄅ") + ZhuyinKeys.symbolCode('ㄋ')))
        assertEquals("ㄋㄧ", nine.spelt(Syllables.id("ni")))
        assertEquals("ㄓ", nine.started(Syllables.id("zhong"), 1))
        assertEquals("ni", T9Segmenter().spelt(Syllables.id("ni")))
    }

    @Test
    fun aKeysInitialsAreThePinyinOnesOfItsInitialSymbols() {
        assertEquals(listOf("zh", "z"), nine.initialsOf(ZhuyinKeys.groupCode(2)))
        assertEquals(listOf("r"), nine.initialsOf(ZhuyinKeys.groupCode(10)))
        assertEquals(listOf("n"), nine.initialsOf(ZhuyinKeys.symbolCode('ㄋ')))
        assertEquals(emptyList<String>(), dachen.initialsOf(ZhuyinKeys.symbolCode('ㄚ')))
    }

    @Test
    fun aToneSeparatesAndAToneOrSymbolIsRead() {
        assertTrue(dachen.separates(ZhuyinKeys.toneCode(2)))
        assertTrue(dachen.separates('\''))
        assertFalse(dachen.separates(ZhuyinKeys.symbolCode('ㄅ')))
        assertTrue(dachen.reads(ZhuyinKeys.toneCode(5)))
        assertTrue(nine.reads(ZhuyinKeys.symbolCode('ㄅ')))
        assertTrue(nine.reads(ZhuyinKeys.groupCode(0)))
        assertFalse(nine.reads('ㄅ'))
        assertFalse(T9Segmenter().reads(ZhuyinKeys.toneCode(1)))
        assertEquals(ZhuyinKeys.toneCode(1), dachen.spaceTone)
        assertNull(nine.spaceTone)
        assertNull(T9Segmenter().spaceTone)
    }

    // a custom phrase's key is pinyin's letters: zhuyin typed finds it (design 051 §3.3)
    @Test
    fun zhuyinTypedReadsAsAPhrasesPinyinKey() {
        fun keys(typed: String, on: T9Segmenter) = PhraseKeys.of(typed, on.letters, ways = on::phraseWays)!!
        assertTrue(keys(dachen("ㄋㄧˇㄏㄠˇ"), dachen).reads("nihao"))
        assertTrue(keys(dachen("ㄖㄑ"), dachen).reads("rq"))
        assertTrue(keys(nine("ㄋㄧㄏㄠ"), nine).reads("nihao"))
        assertTrue(keys(dachen("ㄧㄡ"), dachen).reads("you"))
        assertFalse(keys(dachen("ㄋㄧ"), dachen).reads("nihao"))
        assertNull(T9Segmenter().phraseWays('2'))
    }
}
