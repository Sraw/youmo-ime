/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters.Companion.G18
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters.Companion.NINE_SP
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.MICROSOFT
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.XIAOHE
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.ZIRANMA
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
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
}
