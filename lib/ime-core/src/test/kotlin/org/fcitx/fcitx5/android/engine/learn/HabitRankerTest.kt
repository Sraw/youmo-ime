/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.assertRows
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
        val spelled = HashMap<Pair<String, String>, Habits.Spelled>()
        override fun spelled(kind: String, words: Words) = spelled[kind to words.text] ?: Habits.Spelled.NONE

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

    // U-20 (D32 B): words read so, two syllables each
    private fun read(text: String, reading: String) = Item(text, Words.of(text, reading))

    private val fh = Code(Kinds.FULL, "fh")
    private val fhItems = arrayOf(
        read("发挥", "fa hui"), read("符合", "fu he"), read("返回", "fan hui"), read("防护", "fang hu"), read("奉化", "feng hua"),
    )

    // 奉化 used twice spelt in full goes third under fh; the first two stay
    @Test
    fun aWordUsedTwiceSpeltInFullGoesThirdUnderItsInitials() {
        view.spelled[Kinds.FULL to "奉化"] = Habits.Spelled(1, 0)
        assertEquals(4, order(fh, "", *fhItems).indexOf("奉化"))
        view.spelled[Kinds.FULL to "奉化"] = Habits.Spelled(2, 0)
        assertEquals(listOf("发挥", "符合", "奉化", "返回", "防护"), order(fh, "", *fhItems))
        // never first, nor second, by full pinyin alone: still so with only two items before it
        assertEquals(listOf("发挥", "符合", "奉化"), order(fh, "", fhItems[0], fhItems[1], fhItems[4]))
    }

    // used under fh itself it goes by U-02 there (twice picked: first); the full spelling's count no matter
    @Test
    fun usedUnderTheInitialsItGoesByThem() {
        view.spelled[Kinds.FULL to "奉化"] = Habits.Spelled(2, 0)
        view.used(fh, "奉化", 2, corrected = 2)
        assertEquals("奉化", order(fh, "", *fhItems).first())
        // 知道 used under zd stays first over 指导 used spelt in full
        val zd = Code(Kinds.FULL, "zd")
        val items = arrayOf(read("知道", "zhi dao"), read("指导", "zhi dao"), read("制度", "zhi du"), read("战斗", "zhan dou"), read("站点", "zhan dian"))
        view.spelled[Kinds.FULL to "指导"] = Habits.Spelled(9, 0)
        view.used(zd, "知道", 4)
        assertEquals(listOf("知道", "指导"), order(zd, "", *items).take(2))
    }

    // corrected to under dl: spelt in full or in part never below second; the counts gone, the floor goes
    @Test
    fun aWordCorrectedToUnderItsInitialsIsNoLowerThanSecondSpeltOut() {
        val items = arrayOf(read("电量", "dian liang"), read("点亮", "dian liang"), read("店里", "dian li"), read("点了", "dian le"))
        val dianl = Code(Kinds.FULL, "dianl")
        view.spelled[Kinds.FULL to "点了"] = Habits.Spelled(0, 1)
        assertEquals(listOf("电量", "点了", "点亮", "店里"), order(dianl, "", *items))
        assertEquals(1, order(Code(Kinds.FULL, "dianle"), "", *items).indexOf("点了"))
        // under the initials themselves no floor: U-02 there
        assertEquals(3, order(dl, "", *items).indexOf("点了"))
        view.spelled.clear()
        assertEquals(3, order(dianl, "", *items).indexOf("点了"))
    }

    // where each lifted or floored word goes, a fresh record a row
    @Test
    fun theLiftedAndFlooredGoWhereTheirPlacesAre() {
        val dianl = Code(Kinds.FULL, "dianl")
        val dianItems = listOf(read("电量", "dian liang"), read("点亮", "dian liang"), read("店里", "dian li"), read("点了", "dian le"))
        class Case(val why: String, val code: Code, val items: List<Item<String>>, val pin: Words?, val spelled: Map<String, Habits.Spelled>, val order: List<String>)
        val rows = listOf(
            Case("a word already ahead is not moved; the second lifted goes after the first", fh, fhItems.toList(), null,
                mapOf("符合" to Habits.Spelled(5, 0), "奉化" to Habits.Spelled(3, 0), "防护" to Habits.Spelled(4, 0)), listOf("发挥", "符合", "防护", "奉化", "返回")),
            Case("one lifted already third stays there, one of more uses further down after it (F3749)", fh,
                listOf(fhItems[0], fhItems[1], fhItems[4], fhItems[2], fhItems[3]), null,
                mapOf("奉化" to Habits.Spelled(2, 0), "防护" to Habits.Spelled(5, 0)), listOf("发挥", "符合", "奉化", "防护", "返回")),
            Case("a pin in front: the lifted word a place down as every other word (F3732)", fh, fhItems.toList(), Words.of("防护", "fang hu"),
                mapOf("奉化" to Habits.Spelled(2, 0)), listOf("防护", "发挥", "符合", "奉化", "返回")),
            Case("the pin stays in front, the floor right after it", dianl, dianItems, Words.of("店里", "dian li"),
                mapOf("点了" to Habits.Spelled(0, 2)), listOf("店里", "点了", "电量", "点亮")),
        )
        assertRows(rows, { it.why }) { row ->
            val v = View()
            row.pin?.let { v.pins[row.code] = it }
            for ((text, n) in row.spelled) v.spelled[Kinds.FULL to text] = n
            assertEquals(row.why, row.order, HabitRanker.rank(row.items, row.code, "", v).map { it.value })
        }
    }

    // under a part both: the floored words second and on, the lifted after them (R14 F3742); a word
    // both corrected to and used in full is floored (F3752); each moved shows why (F3736)
    @Test
    fun underAPartTheFlooredGoFirstAndEachMovedShowsWhy() {
        val items = arrayOf(read("电量", "dian liang"), read("点亮", "dian liang"), read("店里", "dian li"), read("点了", "dian le"), read("电力", "dian li"))
        val dianl = Code(Kinds.FULL, "dianl")
        view.spelled[Kinds.FULL to "点了"] = Habits.Spelled(2, 1)
        view.spelled[Kinds.FULL to "电力"] = Habits.Spelled(0, 1)
        view.spelled[Kinds.FULL to "店里"] = Habits.Spelled(3, 0)
        val ranked = rank(dianl, "", *items)
        assertEquals(listOf("电量", "点了", "电力", "店里", "点亮"), ranked.map { it.value })
        assertEquals(listOf(Tier.DICTIONARY, Tier.FLOORED, Tier.FLOORED, Tier.LIFTED, Tier.DICTIONARY), ranked.map { it.tier })
        // one already at its place shows the rule too, so kept holds it (F3762); one higher keeps its own
        view.spelled.clear()
        view.spelled[Kinds.FULL to "店里"] = Habits.Spelled(3, 0)
        view.spelled[Kinds.FULL to "点亮"] = Habits.Spelled(3, 0)
        val held = rank(dianl, "", *items)
        assertEquals(Tier.LIFTED, held.first { it.value == "店里" }.tier)
        assertEquals(Tier.DICTIONARY, held.first { it.value == "点亮" }.tier)
        // one used here at the floor's place keeps the reason it was used here (F3771)
        view.spelled.clear()
        view.spelled[Kinds.FULL to "点了"] = Habits.Spelled(0, 1)
        view.used(dianl, "电量", 3)
        view.used(dianl, "点了", 1)
        val used = rank(dianl, "", *items)
        assertEquals(listOf("电量", "点了"), used.take(2).map { it.value })
        assertEquals(Tier.USED, used[1].tier)
    }

    // 双拼's typed words read by keys alone (061-sp-abbrev §3.3), a row of its table each: V the decoder's first,
    // D1 D2 the dictionary's after it, A1 to A4 typed words read alone, in the decoder's order
    @Test
    @Uat("SP-55")
    fun wordsTypedReadAloneGoBeforeOrAfterTheFirstByHowTheCodeReads() {
        val sp = Kinds.shuangpin("Ziranma")
        val half = Code(sp, "ufmuh")
        val whole = Code(sp, "umuh")
        fun of(text: String, reading: String) = Item(text, Words.of(text, reading))
        val vItem = of("先前", "xian qian")
        val d1Item = of("现钱", "xian qian")
        val d2Item = of("线圈", "xian quan")
        val typed = listOf(of("下面", "xia mian"), of("小明", "xiao ming"), of("学名", "xue ming"), of("熊猫", "xiong mao")).map { Item(it.value, it.words, alone = true) }
        class Case(val why: String, val code: Code, val order: List<String>, val tiers: Map<String, Tier> = emptyMap(), val items: List<Item<String>> = listOf(vItem, d1Item, d2Item) + typed, val gate: Boolean = false, val set: View.() -> Unit = {})
        val a1 = "下面"; val a2 = "小明"; val a3 = "学名"; val a4 = "熊猫"; val v = "先前"; val d1 = "现钱"; val d2 = "线圈"
        val rows = listOf(
            Case("not whole: A1 V A2 A3, the fourth where it was", half, listOf(a1, v, a2, a3, d1, d2, a4), mapOf(a1 to Tier.ALONE, a3 to Tier.ALONE, a4 to Tier.DICTIONARY)),
            Case("whole: V A1 A2 A3", whole, listOf(v, a1, a2, a3, d1, d2, a4)),
            Case("a pin, not whole: H A1 V A2 A3", half, listOf(d2, a1, v, a2, a3, d1, a4), mapOf(d2 to Tier.PINNED)) { pins[half] = d2Item.words },
            Case("a pin, whole: H V A1 A2 A3", whole, listOf(d2, v, a1, a2, a3, d1, a4)) { pins[whole] = d2Item.words },
            Case("a typed word pinned: first, the rest as ever", half, listOf(a2, a1, v, a3, a4, d1, d2), mapOf(a2 to Tier.PINNED, a1 to Tier.ALONE)) { pins[half] = typed[1].words },
            Case("another floored, not whole: A1 F V A2 A3", half, listOf(a1, d2, v, a2, a3, d1, a4), mapOf(d2 to Tier.FLOORED)) {
                spelled[sp to d2] = Habits.Spelled(0, 1)
            },
            Case("another floored, whole: V F A1 A2 A3", whole, listOf(v, d2, a1, a2, a3, d1, a4), mapOf(d2 to Tier.FLOORED)) {
                spelled[sp to d2] = Habits.Spelled(0, 1)
            },
            Case("a typed word floored, not whole: no lower than second, first already", half, listOf(a1, v, a2, a3, d1, d2, a4), mapOf(a1 to Tier.ALONE)) {
                spelled[sp to a1] = Habits.Spelled(0, 1)
            },
            Case("a typed word floored, whole: second, shown so", whole, listOf(v, a2, a1, a3, d1, d2, a4), mapOf(a2 to Tier.FLOORED, a1 to Tier.ALONE)) {
                spelled[sp to a2] = Habits.Spelled(0, 1)
            },
            Case("a typed word used here once (space): first of them still", half, listOf(a3, v, a1, a2, d1, d2, a4), mapOf(a3 to Tier.ALONE)) {
                used(half, a3, 1)
            },
            Case("another word used here once: after V as ever", half, listOf(a1, v, a2, a3, d2, d1, a4), mapOf(d2 to Tier.USED)) {
                used(half, d2, 1)
            },
            Case("the gate (a typed word half typed): V A1 A2 A3", half, listOf(v, a1, a2, a3, d1, d2, a4), gate = true),
            // code review SHOULD-1 (ce8e9f5f5): V a typed word read alone was put behind the decoder's second
            Case("V itself a typed word, whole: first still, two more after it", whole, listOf(v, a1, a2, d1, d2, a3, a4), mapOf(v to Tier.ALONE),
                items = listOf(Item(vItem.value, vItem.words, alone = true), d1Item, d2Item) + typed),
            Case("V itself a typed word, the gate: first still", half, listOf(v, a1, a2, d1, d2, a3, a4), mapOf(v to Tier.ALONE),
                items = listOf(Item(vItem.value, vItem.words, alone = true), d1Item, d2Item) + typed, gate = true),
            Case("H, then a typed word used here once, the gate: H V A1, the used one no V", half, listOf(d2, v, a3, a1, a2, d1, a4),
                mapOf(d2 to Tier.FIRST_HERE, a3 to Tier.ALONE), gate = true) {
                firsts[half to ""] = d2Item.words
                used(half, a3, 1)
            },
            Case("V itself a typed word, not whole: first as A1, the next as V", half, listOf(v, d1, a1, a2, d2, a3, a4), mapOf(v to Tier.ALONE),
                items = listOf(Item(vItem.value, vItem.words, alone = true), d1Item, d2Item) + typed),
            Case("a typed word used spelt in full twice: not lifted, up front already; another lifted third", half, listOf(a1, v, d2, a2, a3, d1, a4),
                mapOf(a3 to Tier.ALONE, d2 to Tier.LIFTED)) {
                spelled[sp to a3] = Habits.Spelled(2, 0)
                spelled[sp to d2] = Habits.Spelled(2, 0)
            },
            // R27 F8535: the fourth was lifted to third, ahead of the second and third read alone; §3.3 ranks it as one of them
            Case("a fourth typed word used spelt in full twice: where it was, not lifted", half, listOf(a1, v, a2, a3, d1, d2, a4), mapOf(a4 to Tier.DICTIONARY)) {
                spelled[sp to a4] = Habits.Spelled(2, 0)
            },
        )
        assertRows(rows, { it.why }) { row ->
            val view = View().apply(row.set)
            val ranked = HabitRanker.rank(row.items, row.code, "", view, readsWhole = row.code == whole, gate = row.gate)
            assertEquals(row.why, row.order, ranked.map { it.value })
            for ((text, tier) in row.tiers) assertEquals("${row.why}: $text", tier, ranked.first { it.value == text }.tier)
        }
    }
}
