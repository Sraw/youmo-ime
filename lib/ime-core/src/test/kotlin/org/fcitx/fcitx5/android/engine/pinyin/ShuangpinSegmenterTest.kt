/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.MICROSOFT
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.XIAOHE
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.ZIRANMA
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.fcitx.fcitx5.android.engine.pinyin.SyllableMatches.Companion.ABBREV
import org.fcitx.fcitx5.android.engine.pinyin.SyllableMatches.Companion.COMPLETION
import org.fcitx.fcitx5.android.engine.pinyin.SyllableMatches.Companion.FUZZY
import org.fcitx.fcitx5.android.engine.pinyin.SyllableMatches.Companion.TYPO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuangpinSegmenterTest {

    private val xiaohe = ShuangpinSegmenter(XIAOHE)
    private val microsoft = ShuangpinSegmenter(MICROSOFT)

    /** Whether [e] is 简拼 in the middle: a key read alone where it and the next make a syllable. */
    private fun SyllableGraph.abbrev(e: Int): Boolean = matches(e).let { m -> m.size > 0 && (0 until m.size).all { m.flags(it) and ABBREV != 0 } }

    /** Every edge, as `from-to:KIND`. */
    private fun SyllableGraph.edgeSet(): Set<String> = (0 until edgeCount).map { "${from(it)}-${to(it)}:${kind(it)}" }.toSet()

    /** The edges from start to end, as `text:KIND`, when the graph is one path but for 简拼 in the middle. */
    private fun SyllableGraph.path(): List<String> {
        val out = ArrayList<String>()
        var at = start
        while (at < end) {
            val e = edges(at).single { !abbrev(it) }
            out += "${text(e)}:${kind(e)}"
            at = to(e)
        }
        return out
    }

    /**
     * Syllables of the edges from [typed]'s start but 简拼 in the middle, spelt, with `~` for
     * fuzzy, `!` for a typo and `…` for a completion.
     */
    private fun ShuangpinSegmenter.read(typed: String): Set<String> {
        val g = segment(typed)
        return g.edges(g.start).filterNot { g.abbrev(it) }.flatMap { e ->
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
        val schemes = listOf(ZIRANMA, MICROSOFT, XIAOHE, ZIGUANG, ABC, GB)
        for (scheme in schemes) {
            val segmenter = ShuangpinSegmenter(scheme)
            var typed = 0
            for (id in 0 until Syllables.count) {
                val code = scheme.encode(id) ?: continue
                typed++
                assertEquals(2, code.length)
                val g = segmenter.segment(code)
                val e = g.edges(0).single { !g.abbrev(it) }
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
    fun aKeyThatMakesASyllableWithTheNextIsReadAloneTooAsItsInitial() {
        // 自然码 xm is xian; read alone too, x then m (小明): x an initial, m at the end still being typed
        val ziranma = ShuangpinSegmenter(ZIRANMA)
        assertEquals(setOf("0-2:SYLLABLE", "0-1:INITIAL", "1-2:PARTIAL"), ziranma.segment("xm").edgeSet())
        val g = ziranma.segment("xm")
        val x = g.edges(0).single { g.kind(it) == Kind.INITIAL }
        assertTrue(g.abbrev(x))
        // shown flagged (`^`) beside its completion
        assertTrue(g.matches(x).toString(), g.matches(x).toString().split(' ').all { it.endsWith("…^") })
        // the same syllables as x alone at the end, flagged ABBREV on top
        val end = ziranma.segment("x").let { it.matches(it.edges(0).single()) }
        assertEquals((0 until end.size).map { end.syllable(it) to (end.flags(it) or ABBREV) }, g.matches(x).let { m -> (0 until m.size).map { m.syllable(it) to m.flags(it) } })
        // off: the pair only, as before
        assertEquals(setOf("0-2:SYLLABLE"), ShuangpinSegmenter(ZIRANMA, abbreviations = false).segment("xm").edgeSet())
        // where the pair is no syllable the key alone is the plain initial, read either way
        assertEquals(ShuangpinSegmenter(ZIRANMA, abbreviations = false).segment("fx").edgeSet(), ziranma.segment("fx").edgeSet())
        assertTrue(ziranma.segment("fx").let { f -> (0 until f.edgeCount).none { f.abbrev(it) } })
    }

    @Test
    fun theLastKeyAloneIsStillBeingTypedNotAnAbbreviation() {
        // 小鹤 xnm 小明: xiao whole, m the last key: PARTIAL, as before, with no ABBREV
        val g = xiaohe.segment("xnm")
        val m = g.edges(2).single()
        assertEquals(Kind.PARTIAL, g.kind(m))
        assertTrue((0 until g.matches(m).size).all { g.matches(m).flags(it) and ABBREV == 0 })
        // no abbreviation ends the input: it is always a pair or the last key, never a key read alone before it
        for (typed in listOf("xm", "xnm", "nihc", "umuh")) {
            val graph = xiaohe.segment(typed)
            assertTrue(typed, (0 until graph.edgeCount).none { graph.abbrev(it) && graph.to(it) == graph.end })
        }
    }

    @Test
    fun everyKeyMayBeAnInitialSoAllInitialsReadAcrossPairs() {
        // 小鹤 umuh 什么时候 by its initials: mu and uh (shang) are pairs, read alone too
        val g = xiaohe.segment("umuh")
        val oneKeyEach = (0 until 4).map { at -> g.edges(at).filter { g.to(it) == at + 1 }.map { g.kind(it) to g.abbrev(it) }.toSet() }
        assertEquals(
            listOf(
                setOf(Kind.INITIAL to false), // um: shian is no syllable
                setOf(Kind.INITIAL to true),
                setOf(Kind.INITIAL to true),
                setOf(Kind.PARTIAL to false),
            ),
            oneKeyEach,
        )
        // off, mu and uh are only their pairs: no way through by single keys
        val off = ShuangpinSegmenter(XIAOHE, abbreviations = false).segment("umuh")
        assertTrue(off.edges(1).none { off.to(it) == 2 })
    }

    @Test
    fun wholeSyllablesKeepTheirEdgesUnflagged() {
        // the pairs of input typed in whole syllables are as before, abbreviations only added beside
        // them; what the decoder makes of it is UAT's (ShuangpinUatTest: whole syllables read as before)
        val sentence = listOf("wo", "men", "zhong", "guo", "ren", "chi", "shen", "me", "an", "ai", "er", "xue")
        for (scheme in listOf(ZIRANMA, MICROSOFT, XIAOHE, ZIGUANG, ABC, GB)) {
            val typed = sentence.joinToString("") { scheme.encode(Syllables.id(it))!! }
            val on = ShuangpinSegmenter(scheme).segment(typed)
            val off = ShuangpinSegmenter(scheme, abbreviations = false).segment(typed)
            assertEquals(typed, off.path(), on.path())
            val before = off.edgeSet()
            for (e in 0 until on.edgeCount) {
                if ("${on.from(e)}-${on.to(e)}:${on.kind(e)}" in before && !on.abbrev(e)) continue
                // what is new is the key alone, or what follows it off the pairs (an odd start)
                assertTrue("$typed ${on.from(e)}", on.abbrev(e) || on.from(e) % 2 == 1)
            }
        }
    }

    @Test
    fun aSemicolonAfterAWholeSyllableIsNoFinalThoughAKeyAloneWouldReadIt() {
        // 微软 lj is lan: l alone (简拼 in the middle) then j; (jing) would read ; as ing (review M1)
        assertFalse(microsoft.readsAfter("lj", ';'))
        assertFalse(microsoft.readsAfter("woxm", ';'))
        assertTrue(microsoft.readsAfter("x", ';'))
        assertTrue(microsoft.readsAfter("wox", ';'))
    }

    @Test
    fun withoutAbbreviationsIsTheGraphTypedInWholeSyllables() {
        val ziranma = ShuangpinSegmenter(ZIRANMA)
        val off = ShuangpinSegmenter(ZIRANMA, abbreviations = false)
        for (typed in listOf("xm", "ufmuh", "umuh", "xwmm", "x'm", "yivz")) {
            assertEquals(typed, off.segment(typed).edgeSet(), ziranma.segment(typed).withoutAbbreviations().edgeSet())
        }
        assertTrue(ziranma.segment("xm").hasAbbreviations)
        assertFalse(ziranma.segment("xm").withoutAbbreviations().hasAbbreviations)
        // none to take off: the graph itself
        val full = PinyinSegmenter().segment("xian")
        assertFalse(full.hasAbbreviations)
        assertSame(full, full.withoutAbbreviations())
    }

    @Test
    fun aCodeReadsWholeWhereItIsAllPairs() {
        val ziranma = ShuangpinSegmenter(ZIRANMA)
        fun whole(typed: String) = ziranma.segment(typed).let { it.readsWhole(it.start, it.end) }
        assertTrue(whole("xm")) // xian
        assertTrue(whole("xwmm")) // xia mian
        assertFalse(whole("ufmuh")) // shen mu, h begun
        assertFalse(whole("umuh")) // u alone: um is no syllable
        assertFalse(whole("x"))
        assertFalse(whole("x'm"))
        // a stretch of it: xian of xmx
        val xmx = ziranma.segment("xmx")
        assertTrue(xmx.readsWhole(0, 2))
        assertFalse(xmx.readsWhole(0, 3))
        assertFalse(xmx.readsWhole(1, 2))
    }

    @Test
    fun aKeyReadAloneTakesItsFuzzyPartnersAsAtTheEnd() {
        // 自然码 with z=zh: zl (zai) a pair, z alone in the middle reads zh's syllables too, fuzzy
        val fuzzy = ShuangpinSegmenter(ZIRANMA, setOf(Fuzzy.Z_ZH))
        val g = fuzzy.segment("zlzl")
        val m = g.matches(g.edges(0).single { g.abbrev(it) })
        val flags = (0 until m.size).associate { Syllables.spelling(m.syllable(it)) to m.flags(it) }
        assertEquals(COMPLETION or ABBREV, flags["zai"])
        assertEquals(FUZZY or COMPLETION or ABBREV, flags["zhai"])
        val end = fuzzy.segment("z").let { it.matches(it.edges(0).single()) }
        assertEquals((0 until end.size).map { end.syllable(it) to (end.flags(it) or ABBREV) }, (0 until m.size).map { m.syllable(it) to m.flags(it) })
    }

    @Test
    fun aSeparatorEndsAKeyAloneAsBefore() {
        // 自然码 x'm: no pair across the separator: x the plain initial, m begun, nothing flagged
        val g = ShuangpinSegmenter(ZIRANMA).segment("x'm")
        assertEquals(listOf("x:INITIAL", "m:PARTIAL"), g.path())
        assertFalse(g.hasAbbreviations)
    }

    @Test
    fun zhChShAloneAreTheirInitialsInEachScheme() {
        for (scheme in listOf(ZIRANMA, MICROSOFT, XIAOHE, ZIGUANG, ABC, GB)) {
            val segmenter = ShuangpinSegmenter(scheme)
            for ((initial, whole) in listOf("zh" to "zhong", "ch" to "chi", "sh" to "shuo")) {
                val code = scheme.encode(Syllables.id(whole))!!
                // the key alone before the guo pair, in the middle
                val g = segmenter.segment(code + scheme.encode(Syllables.id("guo"))!!)
                val alone = g.edges(0).single { g.abbrev(it) }
                val m = g.matches(alone)
                val read = (0 until m.size).map { Syllables.spelling(m.syllable(it)) }
                // and those with no initial the key leads (智能ABC's e is ch and the e of er)
                assertTrue("$code $read", read.isNotEmpty() && read.all { it.startsWith(initial) || it[0] in "aeo" })
                assertTrue("$code $read", read.none { it[0] in "zcs" && !it.startsWith(initial) })
                assertTrue("$code $read", whole in read)
            }
        }
    }

    @Test
    fun keysMustBeTypable() {
        for (keys in listOf("zh=V", "zh='", "zh=vv", "zh=", "=v", "ueng=x", "iang2=d")) {
            assertThrows(keys, IllegalArgumentException::class.java) { ShuangpinScheme(keys, "o") }
        }
        assertThrows(IllegalArgumentException::class.java) { ShuangpinScheme("", "O") }
    }

    private companion object {
        // three of the schemes 0.5 dropped (SchemeMigration), libime's tables kept here: a no-initial
        // key that types an initial too (紫光's and 智能ABC's a), and 国标's zero key a
        val ZIGUANG = ShuangpinScheme(
            "zh=u ch=a sh=i ai=p an=r ang=s ao=q ei=k en=w eng=t er=j ia=x ian=f iang=g iao=b ie=d in=y ing=; " +
                "iong=h iu=j ong=h ou=z ua=x uai=y uan=l uang=g ue=n ui=n un=m uo=o ve=n v=v",
            "o",
        )
        val ABC = ShuangpinScheme(
            "zh=a ch=e sh=v ai=l an=j ang=h ao=k ei=q en=f eng=g er=r ia=d ian=w iang=t iao=z ie=x in=c ing=y " +
                "iong=s iu=r ong=s ou=b ua=d uai=c uan=p uang=t ue=m ui=m un=n uo=o ve=m v=v",
            "o",
        )
        val GB = ShuangpinScheme(
            "zh=v ch=i sh=u ai=k an=f ang=g ao=c ei=b en=r eng=h er=l ia=q ian=d iang=n iao=m ie=t in=l ing=j " +
                "iong=s iu=y ong=s ou=p ua=q uai=y uan=w uang=n ue=x ui=v un=z uo=o ve=x v=v",
            "a",
        )
    }
}
