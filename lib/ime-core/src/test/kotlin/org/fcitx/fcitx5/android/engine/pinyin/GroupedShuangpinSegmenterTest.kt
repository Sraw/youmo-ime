/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.assertRows
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

/** [GroupedShuangpinSegmenter]: 双拼 on the 18 keys and the nine keys. */
class GroupedShuangpinSegmenterTest {

    private fun codes(keys: String, letters: NineKeyLetters) = keys.map(letters::key).joinToString("")

    // the syllables of the edges from the start, spelt
    private fun GroupedShuangpinSegmenter.read(typed: String): Map<Kind, Set<String>> {
        val g = segment(typed)
        return g.edges(g.start).groupBy { g.kind(it) }.mapValues { (_, es) ->
            es.flatMap { e -> g.matches(e).let { m -> (0 until m.size).map { Syllables.spelling(m.syllable(it)) } } }.toSet()
        }
    }

    // the bar with 编码行显示双拼按键: what the code row shows once taken, so a syllable its scheme code, an initial its key
    @Test
    fun aBarOfferedSyllableOrInitialShowsAsTheKeysTheCodeShowsTaken() = assertRows(
        "自然码 sha" to { assertEquals("ua", GroupedShuangpinSegmenter(ZIRANMA, G14).keysOffered("sha")) },
        "自然码 sh" to { assertEquals("u", GroupedShuangpinSegmenter(ZIRANMA, G14).keysOffered("sh")) },
        "自然码 zh, ch" to { assertEquals(listOf("v", "i"), listOf("zh", "ch").map { GroupedShuangpinSegmenter(ZIRANMA, G18).keysOffered(it) }) },
        "自然码 c, a plain initial" to { assertEquals("c", GroupedShuangpinSegmenter(ZIRANMA, G18).keysOffered("c")) },
        "自然码 jing" to { assertEquals("jy", GroupedShuangpinSegmenter(ZIRANMA, NINE_SP).keysOffered("jing")) },
        "小鹤 jing, zhong" to { assertEquals(listOf("jk", "vs"), listOf("jing", "zhong").map { GroupedShuangpinSegmenter(XIAOHE, G18).keysOffered(it) }) },
        "自然码 ang, zero initial" to { assertEquals("ah", GroupedShuangpinSegmenter(ZIRANMA, G18).keysOffered("ang")) },
        "小鹤 an, as it teaches" to { assertEquals("an", GroupedShuangpinSegmenter(XIAOHE, G18).keysOffered("an")) },
        "微软 xing" to { assertEquals("x;", GroupedShuangpinSegmenter(MICROSOFT, G18).keysOffered("xing")) },
        "neither" to { assertEquals(null, GroupedShuangpinSegmenter(ZIRANMA, G18).keysOffered("xyz")) },
        "every scheme, every keyboard: each syllable its taught code, each initial a key taking it" to {
            for ((name, scheme) in EngineSettings.SCHEMES) {
                for (letters in listOf(G18, G14, NINE_SP)) {
                    val segmenter = GroupedShuangpinSegmenter(scheme, letters)
                    // m, n: initials too, offered as such
                    val typed = (0 until Syllables.count).filter { Syllables.spelling(it) !in SpellingIndex.INITIALS }
                        .mapNotNull { id -> scheme.encode(id)?.takeIf { code -> code.all { letters.isCode(letters.key(it)) } }?.let { id to it } }
                    for ((id, code) in typed) assertEquals("$name ${Syllables.spelling(id)}", code, segmenter.keysOffered(Syllables.spelling(id)))
                    for (initial in SpellingIndex.INITIALS) {
                        val key = segmenter.keysOffered(initial) ?: continue
                        assertTrue("$name $initial $key", initial in scheme.initialsOn(key.single()))
                    }
                }
            }
        },
    )

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
        val read = GroupedShuangpinSegmenter(XIAOHE, G18).read(codes("bb", G18))
        assertTrue(read.toString(), read.getValue(Kind.SYLLABLE).containsAll(listOf("bin", "biao", "nin", "niao")))
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

    private fun marked(on: GroupedShuangpinSegmenter, typed: String, kind: Kind): Set<String> {
        val g = on.segment(typed)
        return g.edges(0).filter { g.kind(it) == kind }.flatMap { g.matches(it).toString().split(' ') }.toSet()
    }

    private val ing = GroupedShuangpinSegmenter(ZIRANMA, G18, setOf(Fuzzy.IN_ING))

    @Test
    fun aTakenSyllableOrInitialReadsItsFuzzyPartner() = assertRows(
        "a taken syllable reads its partner" to { assertEquals(setOf("jing", "jin~"), marked(ing, "jing'", Kind.SYLLABLE)) },
        "with all off a taken syllable reads itself" to { assertEquals(setOf("jing"), marked(GroupedShuangpinSegmenter(ZIRANMA, G18), "jing'", Kind.SYLLABLE)) },
        "a taken initial at the end reads its partner's syllables" to {
            val n = marked(GroupedShuangpinSegmenter(ZIRANMA, G18, setOf(Fuzzy.L_N)), "n'", Kind.INITIAL)
            assertTrue(n.toString(), "ni…" in n && "li~…" in n)
        },
        "with all off a taken initial reads its own" to { assertTrue(marked(GroupedShuangpinSegmenter(ZIRANMA, G18), "n'", Kind.INITIAL).none { it.startsWith("l") }) },
    )

    // shown as the keys taken, whichever is read: jing's jy, not jin's jn; n's n, not li's l; 自然码 zh's v, not zi's z
    @Test
    fun aTakenSyllableOrInitialShowsItsOwnKeysWhicheverIsRead() {
        assertEquals("jy", ing.keysShown("jing'", Syllables.id("jin")))
        assertEquals("jy", ing.keysShown("jing'", Syllables.id("jing")))
        assertEquals("n", GroupedShuangpinSegmenter(ZIRANMA, G18, setOf(Fuzzy.L_N)).keysShown("n", Syllables.id("li")))
        assertEquals("v", GroupedShuangpinSegmenter(ZIRANMA, G18, setOf(Fuzzy.Z_ZH)).keysShown("zh", Syllables.id("zi")))
        assertEquals("z", GroupedShuangpinSegmenter(ZIRANMA, G18, setOf(Fuzzy.Z_ZH)).keysShown("z", Syllables.id("zhi")))
    }

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
