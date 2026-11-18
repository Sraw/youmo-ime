/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters.Companion.G14
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters.Companion.G18
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters.Companion.NINE_SP
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.MICROSOFT
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.XIAOHE
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.ZIRANMA
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [GroupedShuangpinSegmenter]: 双拼 on the 18 keys and the nine keys (design §1.4). */
class GroupedShuangpinSegmenterTest {

    private fun codes(keys: String, letters: NineKeyLetters) = keys.map(letters::key).joinToString("")

    // the syllables of the edges from the start, spelt
    private fun GroupedShuangpinSegmenter.read(typed: String): Map<Kind, Set<String>> {
        val g = segment(typed)
        return g.edges(g.start).groupBy { g.kind(it) }.mapValues { (_, es) ->
            es.flatMap { e -> g.matches(e).let { m -> (0 until m.size).map { Syllables.spelling(m.syllable(it)) } } }.toSet()
        }
    }

    @Test
    fun everySyllableTypedInEverySchemeReadsBackOnBothKeyboards() {
        for ((name, scheme) in EngineSettings.SCHEMES) {
            for (letters in listOf(G18, NINE_SP)) {
                val segmenter = GroupedShuangpinSegmenter(scheme, letters)
                for (id in 0 until Syllables.count) {
                    val code = scheme.encode(id) ?: continue
                    val g = segmenter.segment(codes(code, letters))
                    val e = g.edges(0).single { g.kind(it) == Kind.SYLLABLE && g.to(it) == 2 }
                    val i = g.matches(e).indexOf(id)
                    assertTrue("$name $code for ${Syllables.spelling(id)} on $letters", i >= 0 && g.matches(e).flags(i) == 0)
                }
            }
        }
    }

    @Test
    fun noSlipIsReadNotEvenUTypedWithTheKeyOfU() {
        // the 18, 14 and nine keys read slipped keys by where a tap fell (061-neighbour-groups), but no ü typo
        // (060-correction-fuzzy-report): 小鹤's jv is no ju there, as it is on 26 keys
        for ((name, scheme) in EngineSettings.SCHEMES) {
            for (letters in listOf(G18, G14, NINE_SP)) {
                val segmenter = GroupedShuangpinSegmenter(scheme, letters)
                val keys = (('a'..'z') + ';').map(letters::key).filter(letters::isCode).distinct()
                val slips = keys.flatMap { a -> keys.map { b -> "$a$b" } }.filter { segmenter.readsASlip(it) }
                assertTrue("$name on $letters: $slips", slips.isEmpty())
            }
        }
        assertTrue(ShuangpinSegmenter(XIAOHE).read("jv").contains("ju"))
    }

    private fun GroupedShuangpinSegmenter.readsASlip(typed: String): Boolean {
        val g = segment(typed)
        return g.edges(g.start).any { e -> g.matches(e).let { m -> (0 until m.size).any { m.flags(it) and SyllableMatches.TYPO != 0 } } }
    }

    private fun ShuangpinSegmenter.read(typed: String): Set<String> {
        val g = segment(typed)
        return g.edges(g.start).flatMap { e -> g.matches(e).let { m -> (0 until m.size).map { Syllables.spelling(m.syllable(it)) } } }.toSet()
    }

    @Test
    fun aGroupLeavesItsSyllablesToTheDecoder() {
        // 小鹤 bn bn: bin, biao, nin, niao (b, n initials; n iao, b in)
        val read = GroupedShuangpinSegmenter(XIAOHE, G18).read(codes("bb", G18))
        assertTrue(read.toString(), read.getValue(Kind.SYLLABLE).containsAll(listOf("bin", "biao", "nin", "niao")))
        // one key at the end is the start of every syllable it leads
        val lead = GroupedShuangpinSegmenter(ZIRANMA, G18).read(codes("n", G18))
        assertTrue(lead.toString(), lead.getValue(Kind.PARTIAL).containsAll(listOf("ni", "bi", "nao")))
    }

    @Test
    fun microsoftsIngIsOnTheKeyOfL() {
        val segmenter = GroupedShuangpinSegmenter(MICROSOFT, G18)
        assertEquals('', G18.key(';'))
        assertTrue(segmenter.read(codes("d;", G18)).getValue(Kind.SYLLABLE).contains("ding"))
        assertTrue(segmenter.reads(G18.key(';')))
        assertFalse(segmenter.reads(';'))
        assertFalse(segmenter.reads('d'))
    }

    @Test
    fun aKeysInitialsAreTheSchemes() {
        // 自然码: v is zh, u sh, i ch; v alone no initial
        assertEquals(listOf("zh"), GroupedShuangpinSegmenter(ZIRANMA, G18).initialsOf(G18.key('v')))
        assertEquals(listOf("t", "sh", "zh"), GroupedShuangpinSegmenter(ZIRANMA, NINE_SP).initialsOf(NINE_SP.key('t')))
        assertEquals(listOf("b", "n"), GroupedShuangpinSegmenter(ZIRANMA, G18).initialsOf(G18.key('b')))
    }

    @Test
    fun aTakenSyllableOrInitialIsReadAsItIs() {
        val segmenter = GroupedShuangpinSegmenter(ZIRANMA, G18)
        val zhang = segmenter.segment("zhang'" + codes("ni", G18))
        assertEquals(listOf("zhang"), zhang.edges(0).map { Syllables.spelling(zhang.matches(it).syllable(0)) })
        val zh = segmenter.segment("zh'")
        assertEquals(Kind.INITIAL, zh.kind(zh.edges(0).single()))
        // how a started syllable shows: its initial, not as many letters as keys
        assertEquals("zh", segmenter.started(Syllables.id("zhang"), 1))
        assertEquals("a", segmenter.started(Syllables.id("an"), 1))
        assertEquals("b", segmenter.asTyped(codes("b", G18)))
    }

    // 061-sp-abbrev §3.1: where a key and the next make a syllable, the key is also read alone, an INITIAL of the
    // syllables it leads, each ABBREV; two keys too; and the graph without them is the one with none
    @Test
    fun aKeyThatMakesASyllableWithTheNextIsReadAloneToo() {
        for (letters in listOf(G18, G14, NINE_SP)) {
            val on = GroupedShuangpinSegmenter(ZIRANMA, letters)
            val off = GroupedShuangpinSegmenter(ZIRANMA, letters, abbreviations = false)
            for (keys in listOf("ky", "ufmuh", "xm", "nihk")) {
                val g = on.segment(codes(keys, letters))
                val alone = (0 until g.edgeCount).filter(g::isAbbreviation)
                // every pair before the last key reads a syllable on the grouped keys: each such key alone
                assertTrue("$letters $keys", alone.isNotEmpty())
                for (e in alone) {
                    assertEquals(Kind.INITIAL, g.kind(e))
                    assertEquals(1, g.to(e) - g.from(e))
                    val m = g.matches(e)
                    assertTrue((0 until m.size).all { m.flags(it) and SyllableMatches.ABBREV != 0 })
                    // the syllables the key leads, as its INITIAL or PARTIAL reading at the end has
                    val lead = on.segment(codes(keys, letters).take(g.from(e) + 1))
                    val led = lead.edges(g.from(e)).filter { lead.kind(it) != Kind.SYLLABLE && !lead.isAbbreviation(it) }.flatMap { x ->
                        lead.matches(x).let { mm -> (0 until mm.size).map { mm.syllable(it) } }
                    }.toSet()
                    assertEquals("$letters $keys ${g.from(e)}", led, (0 until m.size).map { m.syllable(it) }.toSet())
                }
                val without = g.withoutOtherReadings()
                val none = off.segment(codes(keys, letters))
                assertEquals("$letters $keys", none.toString(), without.toString())
                assertFalse((0 until none.edgeCount).any(none::isAbbreviation))
            }
        }
    }
}
