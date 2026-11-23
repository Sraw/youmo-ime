/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.assertRows
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
        assertEquals(setOf("n", "ni"), graph.syllablesFrom(0))
        val ni = graph.edgeOf(0, "ni")
        assertEquals(3, graph.to(ni))
        assertEquals(3, graph.tone(ni))
        assertEquals(setOf("hao"), graph.syllablesFrom(3))
        assertEquals(3, graph.tone(graph.edgeOf(3, "hao")))
        assertEquals(dachen("ㄋㄧ"), graph.text(ni))
        val bare = dachen.segment(dachen("ㄓㄨㄥ"))
        assertEquals(0, bare.tone(bare.edgeOf(0, "zhong")))
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
        assertTrue(graph.syllablesFrom(0).contains("mi"))
    }

    @Test
    fun aSymbolTypedAsItselfKeepsOnlyItsSyllables() {
        val typed = ZhuyinKeys.symbolCode('ㄋ').toString() + nine("ㄧ")
        val read = nine.segment(typed).syllablesFrom(0)
        assertTrue(read.contains("ni"))
        assertFalse(read.contains("mi"))
    }

    // the flags [syllable] is read with from [at], or null if not read
    private fun SyllableGraph.flagsOf(at: Int, syllable: String): Int? = edges(at).firstNotNullOfOrNull { e ->
        matches(e).indexOf(Syllables.id(syllable)).takeIf { it >= 0 }?.let { matches(e).flags(it) }
    }

    private fun on(vararg fuzzy: Fuzzy) = T9Segmenter(fuzzy.toSet(), abbreviations = false, letters = ZhuyinKeys.NINE)

    private val ni = ZhuyinKeys.symbolCode('ㄋ').toString() + nine("ㄧ")
    private val zh = ZhuyinKeys.symbolCode('ㄓ').toString()

    // a taken syllable is its pinyin and a taken initial its symbol typed as itself: each reads the fuzzy pairs on, priced fuzzy
    @Test
    fun aTakenSyllableOrSymbolReadsTheFuzzyPairsOn() = assertRows(
        "a taken syllable reads its partner" to { assertEquals(SyllableMatches.FUZZY, on(Fuzzy.IN_ING).segment("jing'").flagsOf(0, "jin")) },
        "a taken syllable reads itself unflagged" to { assertEquals(0, on(Fuzzy.IN_ING).segment("jing'").flagsOf(0, "jing")) },
        "a symbol typed as itself reads its partner's syllables" to { assertEquals(SyllableMatches.FUZZY, on(Fuzzy.L_N).segment(ni).flagsOf(0, "li")) },
        "a symbol typed as itself reads its own unflagged" to { assertEquals(0, on(Fuzzy.L_N).segment(ni).flagsOf(0, "ni")) },
        "ㄓ alone reads zi under z ↔ zh" to { assertEquals(SyllableMatches.FUZZY, on(Fuzzy.Z_ZH).segment(zh).flagsOf(0, "zi")) },
        "with all off a taken syllable reads no partner" to { assertNull(nine.segment("jing'").flagsOf(0, "jin")) },
        "with all off a symbol reads no partner" to { assertNull(nine.segment(ni).flagsOf(0, "li")) },
        "with all off ㄓ reads no zi" to { assertNull(nine.segment(zh).flagsOf(0, "zi")) },
    )

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

    @Test
    fun theBarsSyllablesAndInitialsAreShownInZhuyin() {
        assertEquals("ㄋㄧ", nine.shownOffered("ni"))
        assertEquals("ㄓㄨㄥ", nine.shownOffered("zhong"))
        assertEquals("ㄓ", nine.shownOffered("zh"))
        assertEquals("ㄖ", nine.shownOffered("r"))
        assertEquals("ㄇ", nine.shownOffered("m"))
        assertEquals("xyz", nine.shownOffered("xyz"))
        val pinyin = T9Segmenter(abbreviations = false, letters = NineKeyLetters.STANDARD)
        assertEquals("ni", pinyin.shownOffered("ni"))
        assertEquals("r", pinyin.shownOffered("r"))
    }

    @Test
    fun aSyllableTakenOffTheBarIsShownInZhuyinBesideTheKeysTyped() {
        // mi taken (its pinyin in the input, then the separator), then ㄏㄠ's groups and a tone
        val typed = "mi'" + nine("ㄏㄠ") + ZhuyinKeys.toneCode(3)
        val shown = nine.asTyped(typed)
        assertTrue(shown, shown.startsWith("ㄇㄧ"))
        assertTrue(shown, shown.endsWith("ˇ"))
        assertFalse(shown, shown.any { it in 'a'..'z' })
        assertEquals("ㄖ", nine.asTyped("r"))
        val pinyin = T9Segmenter(abbreviations = false, letters = NineKeyLetters.STANDARD)
        assertEquals(NineKeyLetters.STANDARD.asTyped("mi'"), pinyin.asTyped("mi'"))
    }
}
