/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.ABC
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.GB
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.MICROSOFT
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.PINYINJIAJIA
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.XIAOHE
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.ZHONGWENZHIXING
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.ZIGUANG
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.ZIRANMA
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.fcitx.fcitx5.android.engine.pinyin.SyllableMatches.Companion.COMPLETION
import org.fcitx.fcitx5.android.engine.pinyin.SyllableMatches.Companion.FUZZY
import org.fcitx.fcitx5.android.engine.pinyin.SyllableMatches.Companion.TYPO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuangpinSegmenterTest {

    private val xiaohe = ShuangpinSegmenter(XIAOHE)
    private val microsoft = ShuangpinSegmenter(MICROSOFT)

    /** The edges from start to end, as `text:KIND`, when the graph is one path. */
    private fun SyllableGraph.path(): List<String> {
        val out = ArrayList<String>()
        var at = start
        while (at < end) {
            val e = edges(at).single()
            out += "${text(e)}:${kind(e)}"
            at = to(e)
        }
        return out
    }

    /**
     * Syllables of the edges from [typed]'s start, spelt, with `~` for fuzzy, `!` for a typo and
     * `…` for a completion.
     */
    private fun ShuangpinSegmenter.read(typed: String): Set<String> {
        val g = segment(typed)
        return g.edges(g.start).flatMap { e ->
            val m = g.matches(e)
            (0 until m.size).map { i ->
                val flags = m.flags(i)
                Syllables.spelling(m.syllable(i)) + (if (flags and FUZZY != 0) "~" else "") +
                    (if (flags and TYPO != 0) "!" else "") + (if (flags and COMPLETION != 0) "…" else "")
            }
        }.toSet()
    }

    @Test
    fun everySyllableTypedInEverySchemeReadsBack() {
        val schemes = listOf(ZIRANMA, MICROSOFT, ZIGUANG, ABC, ZHONGWENZHIXING, PINYINJIAJIA, XIAOHE, GB)
        for (scheme in schemes) {
            val segmenter = ShuangpinSegmenter(scheme)
            var typed = 0
            for (id in 0 until Syllables.count) {
                val code = scheme.encode(id) ?: continue
                typed++
                assertEquals(2, code.length)
                val g = segmenter.segment(code)
                val e = g.edges(0).single()
                assertEquals(code, Kind.SYLLABLE, g.kind(e))
                val i = g.matches(e).indexOf(id)
                assertTrue("$code for ${Syllables.spelling(id)}", i >= 0 && g.matches(e).flags(i) == 0)
            }
            // all but m, n, ng, r and the 26 Latin letters
            assertEquals(Syllables.count - 4 - 26, typed)
        }
    }

    @Test
    fun theKeysOfEachScheme() {
        assertEquals(setOf("xiao"), xiaohe.read("xn"))
        assertEquals(setOf("zhong"), xiaohe.read("vs"))
        assertEquals(setOf("shu"), xiaohe.read("uu"))
        // one key for two finals: the initial tells which
        assertEquals(setOf("jiong"), microsoft.read("js"))
        assertEquals(setOf("dong"), microsoft.read("ds"))
        assertEquals(setOf("bing"), microsoft.read("b;"))
        // ü has its own key in 微软, and lüe is lve or lue
        assertEquals(setOf("lv"), microsoft.read("ly"))
        assertEquals(setOf("lve"), microsoft.read("lv"))
        assertEquals(setOf("lve"), microsoft.read("lt"))
        assertEquals(setOf("xue"), microsoft.read("xt"))
    }

    @Test
    fun syllablesWithNoInitial() {
        // 小鹤: led by the final's first letter, and two-letter ones as spelt
        assertEquals(setOf("a"), xiaohe.read("aa"))
        assertEquals(setOf("ang"), xiaohe.read("ah"))
        assertEquals(setOf("ai"), xiaohe.read("ai"))
        assertEquals(setOf("ai"), xiaohe.read("ad"))
        assertEquals(setOf("ou"), xiaohe.read("oz"))
        // 小鹤 has no key for er
        assertEquals(setOf("er"), xiaohe.read("er"))
        // 微软: o, and spelt where the keys mean nothing else
        assertEquals(setOf("a"), microsoft.read("oa"))
        assertEquals(setOf("an"), microsoft.read("oj"))
        assertEquals(setOf("er"), microsoft.read("or"))
        assertEquals(setOf("ou"), microsoft.read("ou"))
        assertEquals(setOf("ai"), microsoft.read("ai"))
        // 国标: a; ao is taken by o, so ao is ac only
        val gb = ShuangpinSegmenter(GB)
        assertEquals(setOf("o"), gb.read("ao"))
        assertEquals(setOf("ao"), gb.read("ac"))
    }

    @Test
    fun theWayASchemeTeaches() {
        fun code(scheme: ShuangpinScheme, spelling: String) = scheme.encode(Syllables.id(spelling))
        assertEquals("aa", code(XIAOHE, "a"))
        assertEquals("aa", code(ZIRANMA, "a"))
        assertEquals("oa", code(MICROSOFT, "a"))
        assertEquals("er", code(XIAOHE, "er"))
        assertEquals("an", code(XIAOHE, "an"))
        assertEquals("ah", code(XIAOHE, "ang"))
        assertEquals("oj", code(MICROSOFT, "an"))
        assertEquals("vs", code(XIAOHE, "zhong"))
        assertNull(code(XIAOHE, "ng"))
        assertNull(code(XIAOHE, "A"))
    }

    @Test
    fun keysArePairedFromTheStart() {
        assertEquals(listOf("ni:SYLLABLE", "hc:SYLLABLE"), xiaohe.segment("nihc").path())
        assertEquals(setOf("hao"), xiaohe.read("hc"))
        // a separator ends the pair, and leading ones are skipped
        assertEquals(listOf("n:INITIAL", "ih:SYLLABLE", "c:PARTIAL"), xiaohe.segment("n'ihc").path())
        assertEquals(1, xiaohe.segment("'ni").start)
        assertEquals(listOf("A:SYLLABLE", "gu:SYLLABLE"), xiaohe.segment("Agu").path())
        assertEquals(listOf("b:INITIAL", "A:SYLLABLE"), xiaohe.segment("bA").path())
        assertEquals(listOf("1:RAW", "ni:SYLLABLE", "中:RAW"), xiaohe.segment("1ni中").path())
        assertEquals(emptyList<String>(), xiaohe.segment("").path())
    }

    @Test
    fun oneKeyAloneStandsForEverySyllableTypedFromIt() {
        val g = xiaohe.segment("nih")
        assertEquals(listOf("ni:SYLLABLE", "h:PARTIAL"), g.path())
        val h = g.matches(g.edges(2).single())
        for (s in listOf("ha", "hao", "hong", "huang")) {
            assertEquals(s, COMPLETION, h.flags(h.indexOf(Syllables.id(s))))
        }
        assertEquals(-1, h.indexOf(Syllables.id("zhong")))
        // biu is no syllable: b stands alone, then q is still being typed
        assertEquals(listOf("b:INITIAL", "q:PARTIAL"), xiaohe.segment("bq").path())
        assertTrue("zhong…" in xiaohe.read("v"))
        // 微软's o starts every syllable with no initial
        assertTrue(microsoft.read("o").containsAll(listOf("a…", "an…", "er…", "ou…")))
    }

    @Test
    fun aVowelAloneIsItsSyllableWhereItTypesNoInitial() {
        // 好啊 in 微软: hk then a, which is ai, an, ao on the way and 啊 whole
        val g = microsoft.segment("hka")
        assertEquals(setOf("a:PARTIAL", "a:SYLLABLE"), g.edges(2).map { "${g.text(it)}:${g.kind(it)}" }.toSet())
        assertEquals(setOf("a", "ai…", "an…", "ao…"), microsoft.read("a"))
        assertTrue("e" in ShuangpinSegmenter(ZIGUANG).read("e"))
        assertTrue("o" in ShuangpinSegmenter(GB).read("o"))
        // in 紫光 and 智能ABC a types an initial, so alone it is only the start of one
        assertTrue("a" !in ShuangpinSegmenter(ZIGUANG).read("a"))
        assertTrue("a" !in ShuangpinSegmenter(ABC).read("a"))
    }

    @Test
    fun üTypedWithItsOwnKeyIsASlip() {
        assertEquals(setOf("ju!"), xiaohe.read("jv"))
        assertEquals(setOf("ju"), xiaohe.read("ju"))
        // 微软 types ü with y and ve with v
        assertEquals(setOf("qu!"), microsoft.read("qy"))
        assertEquals(setOf("jue!"), microsoft.read("jv"))
        assertEquals(listOf("j:INITIAL", "v:PARTIAL"), ShuangpinSegmenter(XIAOHE, typos = false).segment("jv").path())
    }

    @Test
    fun fuzzySoundsReadBothWays() {
        val fuzzy = ShuangpinSegmenter(XIAOHE, setOf(Fuzzy.Z_ZH, Fuzzy.AN_ANG))
        assertEquals(setOf("zong", "zhong~"), fuzzy.read("zs"))
        assertEquals(setOf("zhong", "zong~"), fuzzy.read("vs"))
        assertEquals(setOf("an", "ang~"), fuzzy.read("aj"))
        assertEquals(setOf("an", "ang~"), fuzzy.read("an"))
        // ou's partner u has no syllable of its own: uu stays shu, not 欧
        val ou = ShuangpinSegmenter(XIAOHE, setOf(Fuzzy.U_OU))
        assertEquals(setOf("shu", "shou~"), ou.read("uu"))
        // 微软's fuzzy ou (o, then u) leaves ou spelt exactly
        assertEquals(setOf("ou"), ShuangpinSegmenter(MICROSOFT, setOf(Fuzzy.U_OU)).read("ou"))
    }

    @Test
    fun aSchemeMayGiveOnePartSeveralKeys() {
        val scheme = ShuangpinScheme("ing=; ing=y zh=v", "o")
        val segmenter = ShuangpinSegmenter(scheme)
        assertEquals(setOf("bing"), segmenter.read("b;"))
        assertEquals(setOf("bing"), segmenter.read("by"))
        assertEquals("b;", scheme.encode(Syllables.id("bing")))
        // z still types z as well as zh standing for it
        assertEquals(setOf("zhi"), segmenter.read("vi"))
        assertEquals(setOf("zi"), segmenter.read("zi"))
    }

    @Test
    fun keysMustBeTypable() {
        for (keys in listOf("zh=V", "zh='", "zh=vv", "zh=", "=v", "ueng=x", "iang2=d")) {
            assertThrows(keys, IllegalArgumentException::class.java) { ShuangpinScheme(keys, "o") }
        }
        assertThrows(IllegalArgumentException::class.java) { ShuangpinScheme("", "O") }
    }
}
