/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.fcitx.fcitx5.android.engine.pinyin.SyllableMatches.Companion.COMPLETION
import org.fcitx.fcitx5.android.engine.pinyin.SyllableMatches.Companion.FUZZY
import org.fcitx.fcitx5.android.engine.pinyin.SyllableMatches.Companion.TYPO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PinyinSegmenterTest {

    private val plain = PinyinSegmenter()

    /** Every cut into whole syllables, as `xi'an`. */
    private fun SyllableGraph.cuts(): Set<String> {
        val out = HashSet<String>()
        fun walk(at: Int, path: List<String>) {
            if (at == end) out += path.joinToString("'")
            for (e in edges(at)) if (kind(e) == Kind.SYLLABLE) walk(to(e), path + text(e))
        }
        walk(start, emptyList())
        return out
    }

    /** The edges leaving [at], as `text:KIND`. */
    private fun SyllableGraph.leaving(at: Int) = edges(at).map { "${text(it)}:${kind(it)}" }.toSet()

    private fun SyllableGraph.edge(text: String, kind: Kind) =
        (0 until edgeCount).single { text(it) == text && kind(it) == kind }

    /** Flags of [syllable] in [edge]'s matches, or null if it is not among them. */
    private fun SyllableGraph.flags(edge: Int, syllable: String): Int? {
        val m = matches(edge)
        val i = m.indexOf(Syllables.id(syllable))
        return if (i < 0) null else m.flags(i)
    }

    private fun matchesOf(segmenter: PinyinSegmenter, typed: String): SyllableMatches? {
        val g = segmenter.segment(typed)
        return g.edges(g.start).firstOrNull { g.to(it) == g.end && g.kind(it) == Kind.SYLLABLE }?.let { g.matches(it) }
    }

    @Test
    fun everyCutIntoSyllablesIsKept() {
        assertEquals(setOf("ni'hao", "ni'ha'o"), plain.segment("nihao").cuts())
        // n is a syllable too (嗯), so xia'n and xi'a'n are cuts as well
        assertEquals(setOf("xian", "xi'an", "xia'n", "xi'a'n"), plain.segment("xian").cuts())
        val fangan = plain.segment("fangan").cuts()
        assertTrue(fangan.toString(), "fang'an" in fangan && "fan'gan" in fangan)
    }

    @Test
    fun aSeparatorForcesACut() {
        val g = plain.segment("xi'an")
        assertEquals(setOf("xi'an", "xi'a'n"), g.cuts())
        assertEquals(setOf("xi:SYLLABLE"), g.leaving(0))
        assertEquals(3, g.to(g.edges(0).first))
    }

    @Test
    fun separatorsBelongToNoEdge() {
        val g = plain.segment("'ni''hao'")
        assertEquals(1, g.start)
        assertEquals(setOf("ni'hao", "ni'ha'o"), g.cuts())
        val ni = g.edge("ni", Kind.SYLLABLE)
        assertEquals(5, g.to(ni))
        assertEquals(9, g.to(g.edge("hao", Kind.SYLLABLE)))
        // a separator is never a node
        assertTrue(g.edges(3).isEmpty() && g.edges(4).isEmpty())
    }

    @Test
    fun anInitialStandsAloneUnlessAVowelFollows() {
        val nh = plain.segment("nh")
        assertEquals(setOf("n:SYLLABLE", "n:INITIAL"), nh.leaving(0))
        assertEquals(setOf("h:INITIAL"), nh.leaving(1))
        assertEquals(COMPLETION, nh.flags(nh.edge("h", Kind.INITIAL), "hao"))
        assertEquals(null, nh.flags(nh.edge("h", Kind.INITIAL), "ni"))

        assertEquals(setOf("n:SYLLABLE", "ni:SYLLABLE"), plain.segment("nihao").leaving(0))
        val zhongg = plain.segment("zhongg")
        assertEquals(setOf("g:INITIAL"), zhongg.leaving(5))
        // zhon is the slip for zhong
        assertEquals(setOf("zhon:SYLLABLE", "zhong:SYLLABLE"), zhongg.leaving(0))
        // the longer initial wins: zh, not z then h
        assertEquals(setOf("zh:INITIAL"), plain.segment("zhg").leaving(0))
        // ng is a syllable, but n before a consonant may still stand alone: 那个
        assertEquals(setOf("n:SYLLABLE", "ng:SYLLABLE", "n:INITIAL"), plain.segment("ng").leaving(0))
    }

    @Test
    fun aVowelStandsForTheSyllablesItStarts() {
        val ag = plain.segment("ag")
        // ag is also on the way to agn, the slip for ang
        assertEquals(setOf("a:SYLLABLE", "a:INITIAL", "ag:PARTIAL"), ag.leaving(0))
        assertEquals(COMPLETION, ag.flags(ag.edge("a", Kind.INITIAL), "ai"))
        assertEquals(null, ag.flags(ag.edge("a", Kind.INITIAL), "e"))
        // only where no longer syllable starts: an is an, not 爱你
        assertEquals(setOf("a:SYLLABLE", "an:SYLLABLE"), plain.segment("anb").leaving(0))
    }

    @Test
    fun anUnfinishedSyllableAtTheEndCompletes() {
        val g = plain.segment("zho")
        assertEquals(setOf("zho:PARTIAL", "zh:INITIAL"), g.leaving(0))
        assertEquals("zhong… zhou…", g.matches(g.edge("zho", Kind.PARTIAL)).toString())
        assertEquals(setOf("zho:PARTIAL", "zh:INITIAL"), plain.segment("zho'ng").leaving(0))
        // not part-way through the input: zho there is zh + o
        assertFalse("zho:PARTIAL" in plain.segment("zhoa").leaving(0))
    }

    @Test
    fun aFinishedSyllableAtTheEndMayGoOn() {
        val zhan = plain.segment("zhan")
        assertEquals(setOf("zha:SYLLABLE", "zhan:SYLLABLE", "zhan:PARTIAL"), zhan.leaving(0))
        assertEquals("zhang…", zhan.matches(zhan.edge("zhan", Kind.PARTIAL)).toString())
        val xia = plain.segment("xia")
        assertEquals("xian… xiang… xiao…", xia.matches(xia.edge("xia", Kind.PARTIAL)).toString())
        // zhon spells zhong only as a slip; finishing it is the better reading
        val zhon = plain.segment("zhon")
        assertEquals("zhong…", zhon.matches(zhon.edge("zhon", Kind.PARTIAL)).toString())
        // nothing to go on to
        assertFalse("zhang:PARTIAL" in plain.segment("zhang").leaving(0))
        assertFalse("zhan:PARTIAL" in plain.segment("zhanb").leaving(0))
        // a separator after a whole syllable closes it
        assertFalse("zhan:PARTIAL" in plain.segment("zhan'g").leaving(0))
        // a finished slip or fuzzy spelling is no start of one: jv goes on to jvan, not to ju
        val jv = plain.segment("jv")
        assertEquals("juan!… jue!… jun!…", jv.matches(jv.edge("jv", Kind.PARTIAL)).toString())
        val zan = PinyinSegmenter(setOf(Fuzzy.Z_ZH)).segment("zan")
        // zhan~ is what zan already is; zhang~ is what zang would be
        assertEquals("zang… zhang~…", zan.matches(zan.edge("zan", Kind.PARTIAL)).toString())
    }

    @Test
    fun fuzzyPairsMatchBothWays() {
        val fuzzy = PinyinSegmenter(setOf(Fuzzy.Z_ZH, Fuzzy.AN_ANG))
        assertEquals("zan zang~ zhan~ zhang~", matchesOf(fuzzy, "zan").toString())
        assertEquals("zan~ zang~ zhan zhang~", matchesOf(fuzzy, "zhan").toString())
        assertEquals("zan", matchesOf(plain, "zan").toString())
        val plainZ = plain.segment("zg").let { it.matches(it.edge("z", Kind.INITIAL)) }
        assertEquals(-1, plainZ.indexOf(Syllables.id("zhong")))
        val z = fuzzy.segment("zg").let { it.matches(it.edge("z", Kind.INITIAL)) }
        assertEquals(FUZZY or COMPLETION, z.flags(z.indexOf(Syllables.id("zhong"))))
        assertEquals(COMPLETION, z.flags(z.indexOf(Syllables.id("zong"))))
    }

    @Test
    fun finalPairs() {
        assertEquals("xian xiang~", matchesOf(PinyinSegmenter(setOf(Fuzzy.IAN_IANG)), "xian").toString())
        assertEquals("guan guang~", matchesOf(PinyinSegmenter(setOf(Fuzzy.UAN_UANG)), "guan").toString())
        val uOu = PinyinSegmenter(setOf(Fuzzy.U_OU))
        assertEquals("dou~ du", matchesOf(uOu, "du").toString())
        // the u of ju is ü, which ou is no slip for
        assertEquals("ju", matchesOf(uOu, "ju").toString())
        assertEquals(null, matchesOf(uOu, "jou"))
        val vU = PinyinSegmenter(setOf(Fuzzy.V_U))
        assertEquals("lu~ lv", matchesOf(vU, "lv").toString())
        assertEquals("nu nv~", matchesOf(vU, "nu").toString())
        // only l and n have both
        assertEquals(null, matchesOf(vU, "bv"))
        assertEquals("ju!", matchesOf(vU, "jv").toString())
    }

    @Test
    fun fuzzyPairsDoNotChain() {
        val fuzzy = PinyinSegmenter(setOf(Fuzzy.L_N, Fuzzy.L_R))
        assertEquals("lan nan~ ran~", matchesOf(fuzzy, "lan").toString())
        // n reaches l, and l reaches r, but n does not reach r
        assertEquals("lan~ nan", matchesOf(fuzzy, "nan").toString())
        assertEquals("lan~ ran", matchesOf(fuzzy, "ran").toString())
        // m, n, ng and r are not initial + final, so no rule reaches them
        assertEquals("ng", matchesOf(fuzzy, "ng").toString())
        assertEquals(null, matchesOf(fuzzy, "lg"))
    }

    @Test
    fun commonSlipsStillMatch() {
        assertEquals("zhang!", matchesOf(plain, "zhagn").toString())
        assertEquals("xiong!", matchesOf(plain, "xion").toString())
        assertEquals("ju!", matchesOf(plain, "jv").toString())
        assertEquals("xue!", matchesOf(plain, "xve").toString())
        assertEquals("lve", matchesOf(plain, "lue").toString())
        val strict = PinyinSegmenter(typos = false)
        assertEquals(null, matchesOf(strict, "zhagn"))
        assertEquals("lve", matchesOf(strict, "lue").toString())
        // lue is standard, so fuzzy rules reach it like lve
        assertEquals("lve~ nve", matchesOf(PinyinSegmenter(setOf(Fuzzy.L_N)), "nue").toString())
        // a fuzzy spelling slipped as well carries both flags
        assertEquals(FUZZY or TYPO, PinyinSegmenter(setOf(Fuzzy.Z_ZH)).segment("zagn").let { it.flags(it.edge("zagn", Kind.SYLLABLE), "zhang") })
    }

    @Test
    fun whatIsNoPinyinIsKeptAsTyped() {
        val g = plain.segment("iu1")
        assertEquals(setOf("i:RAW"), g.leaving(0))
        assertEquals(setOf("u:RAW"), g.leaving(1))
        assertEquals(setOf("1:RAW"), g.leaving(2))
        assertEquals(0, g.matches(g.edges(0).first).size)
    }

    @Test
    fun anUpperCaseLetterIsItsOwnSyllable() {
        assertEquals(setOf("A'gu"), plain.segment("Agu").cuts())
    }

    @Test
    fun theEmptyInputHasNoEdges() {
        val g = plain.segment("")
        assertEquals(0, g.edgeCount)
        assertEquals(g.end, g.start)
        assertEquals("", g.toString())
    }

    @Test
    fun everyPathReachesTheEnd() {
        val random = Random(7)
        val letters = "abcdefghijklmnopqrstuvwxyz''1A"
        val segmenters = listOf(PinyinSegmenter(Fuzzy.entries.toSet()), PinyinSegmenter(typos = false))
        repeat(2000) {
            val segmenter = segmenters[it % 2]
            val input = String(CharArray(random.nextInt(0, 24)) { letters[random.nextInt(letters.length)] })
            val g = segmenter.segment(input)
            val reached = BooleanArray(input.length + 1).also { it[g.start] = true }
            for (at in 0..input.length) {
                if (!reached[at]) {
                    assertTrue(g.toString(), g.edges(at).isEmpty())
                    continue
                }
                assertEquals("$input at $at: $g", at == input.length, g.edges(at).isEmpty())
                for (e in g.edges(at)) {
                    assertEquals(at, g.from(e))
                    assertTrue(g.toString(), g.to(e) > at)
                    assertTrue(g.text(e).isNotEmpty() && SyllableGraph.SEPARATOR !in g.text(e))
                    reached[g.to(e)] = true
                }
            }
            assertTrue(input, reached[input.length])
        }
    }
}
