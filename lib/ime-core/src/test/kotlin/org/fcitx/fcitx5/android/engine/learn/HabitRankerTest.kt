/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.learn.HabitRanker.Item
import org.fcitx.fcitx5.android.engine.learn.HabitRanker.Tier
import org.junit.Assert.assertEquals
import org.junit.Test

/** [HabitRanker], the spec's examples (impl/design-1b.md §1, §6): what the record says, the order shown. */
class HabitRankerTest {

    /** The record, as ranking reads it: set by the test, nothing learned by itself. */
    private class View : HabitRanker.View {
        val pins = HashMap<Code, Words>()
        val firsts = HashMap<Pair<Code, String>, Words>()
        val uses = HashMap<Pair<Code, String>, Int>()
        val corrections = HashMap<Pair<Code, String>, Int>()
        val firstUses = HashMap<Pair<Code, String>, Long>()
        val mine = HashMap<String, Long>()
        val imports = HashMap<String, Long>()

        override fun pinned(code: Code) = pins[code]
        override fun first(code: Code, prev: String) = firsts[code to prev]
        override fun uses(code: Code, words: Words) = uses[code to words.text] ?: 0
        override fun corrections(code: Code, words: Words) = corrections[code to words.text] ?: 0
        override fun firstUse(code: Code, words: Words) = firstUses[code to words.text] ?: 0L
        override fun mineOrder(text: String) = mine[text] ?: -1L
        override fun importOrder(text: String) = imports[text] ?: -1L

        fun used(code: Code, text: String, times: Int, corrected: Int = 0, first: Long = 0L) {
            uses[code to text] = times
            corrections[code to text] = corrected
            if (first > 0) firstUses[code to text] = first
        }
    }

    private val view = View()
    private val dl = Code(Kinds.FULL, "dl")
    private val de = Code(Kinds.FULL, "de")

    private fun item(text: String, letters: Boolean = false, injected: Boolean = false, imported: Boolean = false, exact: Boolean = false) =
        Item(text, Words.of(text), single = text.length == 1 && !letters, letters = letters, imported = imported, injected = injected, exact = exact)

    private fun rank(code: Code, prev: String, vararg items: Item<String>) = HabitRanker.rank(items.toList(), code, prev, view)

    private fun order(code: Code, prev: String, vararg items: Item<String>) = rank(code, prev, *items).map { it.value }

    // U-45: 点了 corrected to twice under dl, used more than 带来, is first after any word; 打了 corrected
    // to twice after 我 is first there alone
    @Test
    fun aWordCorrectedToTwiceIsFirstAcrossTheWordsBeforeAndAFirstAfterOneOnlyThere() {
        val items = arrayOf(item("带来"), item("点了"), item("打了"))
        assertEquals(listOf("带来", "点了", "打了"), order(dl, "", *items))
        view.used(dl, "点了", 3, corrected = 2)
        view.used(dl, "带来", 1)
        assertEquals(listOf("点了", "带来", "打了"), order(dl, "他", *items))
        assertEquals(Tier.ACROSS, rank(dl, "他", *items).first().tier)
        view.firsts[dl to "我"] = Words.of("打了")
        view.used(dl, "打了", 2, corrected = 2)
        val after = rank(dl, "我", *items)
        assertEquals(listOf("打了", "点了", "带来"), after.map { it.value })
        assertEquals(Tier.FIRST_HERE, after.first().tier)
        assertEquals(listOf("点了", "打了", "带来"), order(dl, "他", *items))
    }

    // F2739: V, the decoder's own first, is told wherever it was ranked
    @Test
    fun theOriginalFirstIsToldWhereverItIsRanked() {
        val items = arrayOf(item("带来"), item("点了"))
        assertEquals(listOf(true, false), rank(dl, "", *items).map { it.original })
        view.firsts[dl to ""] = Words.of("点了")
        assertEquals(listOf("点了" to false, "带来" to true), rank(dl, "", *items).map { it.value to it.original })
    }

    // U-10: 得 picked once over 的 under de goes before the words not used there, not first
    @Test
    fun aSingleStrayPickGoesSecond() {
        view.used(de, "得", 1, corrected = 1)
        assertEquals(listOf("的", "得", "地"), order(de, "好", item("的"), item("地"), item("得")))
    }

    // rule 2 is for words: a single character V, or one used more, is first only by rule 1
    @Test
    fun singleCharactersAreNotFirstAcrossTheWordsBefore() {
        view.used(de, "地", 5, corrected = 3)
        assertEquals(listOf("的", "地", "得"), order(de, "跑", item("的"), item("得"), item("地")))
        val xian = Code(Kinds.FULL, "xian")
        view.used(xian, "西安", 5, corrected = 3)
        assertEquals(listOf("先", "西安", "现"), order(xian, "", item("先"), item("现"), item("西安")))
    }

    // zhidao and C30: 指导 corrected to twice after 我 only; after 他 the decoder's 知道, used more, stays
    @Test
    fun aWordCorrectedToLessThanItsFirstIsUsedIsNotFirstAcross() {
        val zhidao = Code(Kinds.FULL, "zhidao")
        val items = arrayOf(item("知道"), item("指导"), item("直到"))
        view.used(zhidao, "知道", 10)
        view.used(zhidao, "指导", 2, corrected = 2)
        view.firsts[zhidao to "我"] = Words.of("指导")
        assertEquals(listOf("指导", "知道", "直到"), order(zhidao, "我", *items))
        assertEquals(listOf("知道", "指导", "直到"), order(zhidao, "他", *items))
    }

    // M4: the pin in front, the rest as they would be, a place down, each its own tier
    @Test
    fun aPinGoesInFrontTheRestKeepTheirOrderAndTiers() {
        view.firsts[dl to ""] = Words.of("点了")
        view.used(dl, "点了", 2, corrected = 2)
        view.pins[dl] = Words.of("打了")
        val ranked = rank(dl, "", item("带来"), item("点了"), item("打了"))
        assertEquals(listOf("打了", "点了", "带来"), ranked.map { it.value })
        assertEquals(listOf(Tier.PINNED, Tier.FIRST_HERE, Tier.DICTIONARY), ranked.map { it.tier })
        // pinned again: the last pin is the one
        view.pins[dl] = Words.of("带来")
        assertEquals(listOf("带来", "点了", "打了"), order(dl, "", item("带来"), item("点了"), item("打了")))
    }

    // S6: a pin or a first not among the items (blocked, or not read) is none
    @Test
    fun aPinOrFirstNotAmongTheItemsFallsThrough() {
        view.pins[dl] = Words.of("大连")
        view.firsts[dl to ""] = Words.of("独立")
        assertEquals(listOf("带来", "点了"), order(dl, "", item("带来"), item("点了")))
    }

    // U-27: letters are never put first by the habits; V kept as letters gives way only to the user's first there
    @Test
    fun lettersAreNeverPutFirst() {
        val app = Code(Kinds.FULL, "app")
        view.firsts[app to ""] = Words.of("APP")
        view.used(app, "APP", 3, corrected = 3)
        assertEquals(listOf("app", "APP", "阿婆"), order(app, "", item("app", letters = true), item("APP", letters = true), item("阿婆")))
        view.firsts[app to ""] = Words.of("阿婆")
        assertEquals("阿婆", order(app, "", item("app", letters = true), item("APP", letters = true), item("阿婆")).first())
        // not across the words before, though
        view.firsts.clear()
        view.used(app, "阿婆", 5, corrected = 3)
        assertEquals("app", order(app, "", item("app", letters = true), item("阿婆")).first())
    }

    // U-10, S3: used as often, the word first used under the code first; then the decoder's order
    @Test
    fun equalUsesGoByFirstUseThenTheDecodersOrder() {
        view.used(de, "得", 2, first = 9)
        view.used(de, "地", 2, first = 4)
        view.used(de, "德", 2)
        assertEquals(listOf("的", "地", "得", "德"), order(de, "", item("的"), item("德"), item("得"), item("地")))
    }

    // rule 4: used, then the user's own by when they became theirs, then the dictionary's, imports after the last spelt as the code
    @Test
    fun usedThenMineThenTheDictionarysWithImportsAfterThoseSpeltAsTheCode() {
        val code = Code(Kinds.FULL, "wxm")
        view.used(code, "王小明", 1)
        view.mine["汪小明"] = 7
        view.mine["王晓明"] = 3
        val items = arrayOf(
            item("我想买"), item("无线码", exact = true), item("问小名"), item("汪小明", injected = true),
            item("王晓明", injected = true), item("王小明"), item("吴晓敏", imported = true),
        )
        val ranked = rank(code, "", *items)
        assertEquals(listOf("我想买", "王小明", "王晓明", "汪小明", "无线码", "吴晓敏", "问小名"), ranked.map { it.value })
        assertEquals(
            listOf(Tier.DICTIONARY, Tier.USED, Tier.MINE, Tier.MINE, Tier.DICTIONARY, Tier.IMPORTED, Tier.DICTIONARY),
            ranked.map { it.tier },
        )
    }

    // D24: the imports by their batch and place, not the decoder's order; none spelt as the code: after the dictionary's all (review-1b N1)
    @Test
    fun importsGoByTheirOrderAndAfterEveryDictionaryWordIfNoneIsSpeltAsTheCode() {
        val code = Code(Kinds.FULL, "wxm")
        view.imports["吴晓敏"] = 2
        view.imports["汪晓明"] = 1
        val ranked = rank(code, "", item("我想买"), item("吴晓敏", imported = true), item("汪晓明", imported = true, injected = true), item("问小名"))
        assertEquals(listOf("我想买", "问小名", "汪晓明", "吴晓敏"), ranked.map { it.value })
        assertEquals(listOf(Tier.DICTIONARY, Tier.DICTIONARY, Tier.IMPORTED, Tier.IMPORTED), ranked.map { it.tier })
    }

    // review-G S1: an imported word the decoder had first is never V either, so never first by that (D23)
    @Test
    fun anImportedItemTheDecoderHadFirstIsNotTheOriginalFirst() {
        val code = Code(Kinds.FULL, "wxm")
        val ranked = rank(code, "", item("汪晓明", imported = true), item("王小明", exact = true), item("问小名"))
        assertEquals(listOf("王小明", "汪晓明", "问小名"), ranked.map { it.value })
        assertEquals(Tier.IMPORTED, ranked[1].tier)
    }

    // V is the first the decoder had: an item put in is never V
    @Test
    fun anItemPutInIsNeverTheOriginalFirst() {
        assertEquals(listOf("点了", "带来"), order(dl, "", item("带来", injected = true), item("点了")))
    }
}
