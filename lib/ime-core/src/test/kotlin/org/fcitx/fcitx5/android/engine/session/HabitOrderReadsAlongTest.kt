/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.lattice.Candidate
import org.fcitx.fcitx5.android.engine.lattice.PinyinDecoder
import org.fcitx.fcitx5.android.engine.lattice.WordScorer
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.pinyin.GroupedShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.KeyBoxes
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.SlipCost
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.pinyin.TouchBoxes
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [HabitOrder.readsAlong] reads each word at its place once an order and walks the code read whole only where it must:
 * held to what it did before, every word of a candidate walked on the graph without slips (061-sp-abbrev §3.6: 简拼 in
 * the middle is no slip, kept), on random keys of 全拼, 小鹤 and 自然码 (简拼 in the middle on, on the grouped keyboards
 * too), the nine keys, tapped anywhere on their keys, the decoder's candidates, each cut short and each with a word kept
 * as typed in its place.
 */
class HabitOrderReadsAlongTest {

    private val data = pinyinDataOf(
        Triple("你", "ni", -2f), Triple("好", "hao", -2.2f), Triple("你好", "ni hao", -2.5f), Triple("我", "wo", -2f),
        Triple("们", "men", -2.5f), Triple("我们", "wo men", -2.6f), Triple("他", "ta", -2f), Triple("的", "de", -1.8f),
        Triple("是", "shi", -2f), Triple("在", "zai", -2.1f), Triple("家", "jia", -2.4f), Triple("里", "li", -2.4f),
        Triple("家里", "jia li", -3f), Triple("击", "ji", -3f), Triple("夸", "kua", -3f), Triple("了", "le", -2f),
        Triple("击垮了", "ji kua le", -4.5f), Triple("课", "ke", -3f), Triple("看", "kan", -2.5f), Triple("完", "wan", -2.5f),
        Triple("看完", "kan wan", -3f), Triple("下", "xia", -2.5f), Triple("面", "mian", -2.5f), Triple("下面", "xia mian", -3f),
        Triple("先", "xian", -2.5f), Triple("吗", "ma", -2.5f), Triple("妈", "ma", -3f), Triple("什么", "shen me", -2.5f),
        Triple("时候", "shi hou", -2.5f), Triple("呀", "ya", -2.6f), Triple("压", "ya", -3f), Triple("鸭", "ya", -3.2f),
    )

    private val order = HabitOrder(
        data, null, CommitRecorder(Learner(data).learnTo(), null, null, { true }),
        PinyinDecoder(data.dictionary, data.vocabulary, WordScorer.of(data.model)), { emptyList() },
    )

    private val decoder = PinyinDecoder(data.dictionary, data.vocabulary, WordScorer.of(data.model))

    // what readsAlong did before: each word walked on [whole] from where the one before ended
    private fun walked(whole: SyllableGraph, c: Candidate): Boolean {
        var from = whole.start
        for (i in c.words.indices) {
            val word = c.words[i]
            if (word != NO_WORD && reading(whole, from, c.ends[i], word, data.dictionary, null) { _, _ -> "" } == null) return false
            from = c.ends[i]
        }
        return true
    }

    // readsAlong without its walk past text kept as typed: a word read over no slip taken to read along
    // [whole] wherever it starts. Where this says yes and [walked] no, only that walk tells them apart
    private fun trustingPastKept(graph: SyllableGraph, whole: SyllableGraph, c: Candidate): Boolean {
        var from = whole.start
        for (i in c.words.indices) {
            val word = c.words[i]
            if (word != NO_WORD) {
                val r = reading(graph, from, c.ends[i], word, data.dictionary, null) { _, _ -> "" } ?: return false
                if (r.slips.isNotEmpty() && reading(whole, from, c.ends[i], word, data.dictionary, null) { _, _ -> "" } == null) return false
            }
            from = c.ends[i]
        }
        return true
    }

    private fun candidate(c: Candidate, words: IntArray, ends: IntArray) = Candidate(c.text, ends.last(), c.score, words, ends)

    // the decoder's candidates, each cut short after each word and with each word in turn kept as typed
    private fun candidates(graph: SyllableGraph): List<Candidate> {
        val d = decoder.decode(graph)
        val out = ArrayList<Candidate>()
        for (c in d.sentences + d.words) {
            for (n in 1..c.words.size) {
                val words = c.words.copyOf(n)
                val ends = c.ends.copyOf(n)
                out += candidate(c, words, ends)
                for (j in 0 until n) if (words[j] != NO_WORD) out += candidate(c, words.copyOf().also { it[j] = NO_WORD }, ends)
            }
        }
        return out
    }

    private class Counts(
        var candidates: Int = 0, var read: Int = 0, var pastKept: Int = 0, var graphs: Int = 0, var onlyWalked: Int = 0,
        val slipped: MutableMap<String, Int> = HashMap(),
    )

    private fun check(what: String, segmenter: Segmenter, keys: String, touches: Array<Touch?>, counts: Counts) {
        val graph = segmenter.segment(keys, null, touches)
        val whole = graph.withoutSlips()
        // one for every candidate, as an order has: a word's place read for one is what the next finds
        val readings = HabitOrder.Readings()
        if (graph.hasOtherReadings) counts.graphs++
        if ((0 until graph.edgeCount).any(graph::isSlip)) counts.slipped.merge(what, 1, Int::plus)
        for (c in candidates(graph)) {
            val expected = walked(whole, c)
            assertEquals("$what $keys ${c.words.toList()} ${c.ends.toList()}", expected, order.readsAlong(graph, whole, c, readings))
            counts.candidates++
            if (expected) counts.read++
            if (expected && c.words.dropLast(1).contains(NO_WORD)) counts.pastKept++
            if (!expected && trustingPastKept(graph, whole, c)) counts.onlyWalked++
        }
    }

    private fun touches(keys: String, board: KeyBoxes, random: Random): Array<Touch?> =
        keys.map { c -> if (random.nextBoolean()) TouchBoxes.tap(board, c) else TouchBoxes.tap(board, c, random.nextFloat() - 0.5f, random.nextFloat() - 0.5f) }
            .toTypedArray()

    @Test
    fun aCandidateReadsAlongTheCodeReadWholeAsEachOfItsWordsWalkedOnIt() {
        val random = Random(61)
        val syllables = listOf("ni", "hao", "wo", "men", "ta", "de", "shi", "zai", "jia", "li", "ji", "kua", "le", "ke", "kan", "wan", "xia", "mian", "ma", "ya")
        val counts = Counts()
        // the floor slips are read over on the keyboards that read most (-4): 全拼's own, -1.75, reads too few to hold
        val full = PinyinSegmenter(neighbours = true, slipCost = SlipCost())
        val shuangpin = listOf(ShuangpinScheme.XIAOHE, ShuangpinScheme.ZIRANMA).map { ShuangpinSegmenter(it, neighbours = true) }
        val nine = T9Segmenter(abbreviations = false, neighbours = true)
        val grouped = listOf(NineKeyLetters.G18 to TouchBoxes.G18, NineKeyLetters.G14 to TouchBoxes.G14, NineKeyLetters.NINE_SP to TouchBoxes.NINE_SP)
        val groupedShuangpin = grouped.associate { (letters, _) -> letters to GroupedShuangpinSegmenter(ShuangpinScheme.XIAOHE, letters, neighbours = true) }
        val zhuyinDachen = T9Segmenter(abbreviations = false, letters = ZhuyinKeys.DACHEN, neighbours = true)
        val zhuyinNine = T9Segmenter(abbreviations = false, letters = ZhuyinKeys.NINE, neighbours = true)
        repeat(400) {
            // 全拼: syllables, some cut to their initial (简拼), now and then a stray letter
            val pinyin = (1..random.nextInt(2, 5)).joinToString("") {
                val s = syllables.random(random)
                when (random.nextInt(6)) {
                    0 -> s.take(1)
                    1 -> s + ('a' + random.nextInt(26))
                    else -> s
                }
            }
            check("全拼", full, pinyin, touches(pinyin, TouchBoxes.QWERTY, random), counts)
            check("九键", nine, pinyin.map { NineKeyLetters.STANDARD.key(it) }.joinToString(""), touches(pinyin.map { NineKeyLetters.STANDARD.key(it) }.joinToString(""), TouchBoxes.NINE, random), counts)
            // 双拼: any letters, nearly every pair a syllable and every key an initial
            val keys = (1..random.nextInt(3, 10)).map { 'a' + random.nextInt(26) }.joinToString("")
            for (s in shuangpin) check("双拼", s, keys, touches(keys, TouchBoxes.QWERTY, random), counts)
            // 双拼's grouped keyboards and zhuyin's (061-neighbour-groups): their slips read otherwise as the others'
            for ((letters, board) in grouped) {
                val codes = keys.map(letters::key).joinToString("")
                check("双拼分组", groupedShuangpin.getValue(letters), codes, touches(codes, board, random), counts)
            }
            val symbols = (1..random.nextInt(2, 8)).map { ZhuyinKeys.SYMBOLS[random.nextInt(ZhuyinKeys.SYMBOLS.length)] }
            val dachen = symbols.map(ZhuyinKeys::symbolCode).joinToString("")
            check("注音", zhuyinDachen, dachen, touches(dachen, TouchBoxes.DACHEN, random), counts)
            val nineZy = symbols.map { y -> ZhuyinKeys.groupCode(ZhuyinKeys.GROUP_SYMBOLS.indexOfFirst { y in it }) }.joinToString("")
            check("注音", zhuyinNine, nineZy, touches(nineZy, TouchBoxes.ZHUYIN_NINE, random), counts)
        }
        // enough of each kind to hold: graphs with slips or 简拼, candidates that read, and ones read past text kept as typed
        assertTrue("${counts.graphs} graphs read otherwise", counts.graphs > 500)
        // of them, ones with a slip of each kind: 简拼 in 双拼 alone would fill the count above
        for (kind in listOf("全拼", "九键", "双拼", "双拼分组", "注音")) assertTrue("${counts.slipped[kind]} $kind graphs with a slip", (counts.slipped[kind] ?: 0) > 150)
        assertTrue("${counts.read} of ${counts.candidates} read", counts.read > 5000 && counts.read < counts.candidates)
        assertTrue("${counts.pastKept} read past text kept as typed", counts.pastKept > 200)
        // and ones that read only if the word after text kept as typed is not walked again
        assertTrue("${counts.onlyWalked} told apart by the walk past text kept as typed", counts.onlyWalked > 40)
    }

    // a word over keys nothing reads slipped is not read: on a long input most are, and reading every one of
    // every candidate took most of what the nine keys' slips cost a key (061-nine-perf-impl-report)
    @Test
    fun onlyTheWordsOverKeysReadSlippedAreRead() {
        // 我们在家里 and 99, its second 9 tapped at its top, toward 6: wo (T9-68)
        val keys = "womenzaijiali".map { NineKeyLetters.STANDARD.key(it) }.joinToString("") + "99"
        val touches = Array<Touch?>(keys.length) { TouchBoxes.tap(TouchBoxes.NINE, keys[it]) }
        touches[keys.length - 1] = TouchBoxes.tap(TouchBoxes.NINE, '9', dy = -0.42f)
        val graph = T9Segmenter(abbreviations = false, neighbours = true).segment(keys, null, touches)
        assertTrue("a slip read", graph.hasSlips)
        val whole = graph.withoutSlips()
        val readings = HabitOrder.Readings()
        val words = HashSet<Triple<Int, Int, Int>>()
        val over = HashSet<Triple<Int, Int, Int>>()
        for (c in decoder.decode(graph).sentences) {
            assertEquals(c.text, walked(whole, c), order.readsAlong(graph, whole, c, readings))
            var from = graph.start
            for (i in c.words.indices) {
                val word = Triple(from, c.ends[i], c.words[i])
                words += word
                if (graph.readsSlipWithin(from, c.ends[i])) over += word
                from = c.ends[i]
            }
        }
        assertTrue("${over.size} of ${words.size} words over the slip", over.isNotEmpty() && over.size < words.size)
        assertTrue("${readings.read} read, ${over.size} over the slip", readings.read <= over.size)
    }

    // the skip takes each word for one the graph reads at its place, as every candidate an order ranks is (the decoder's,
    // or one walked on the graph there): a word it does not read, over keys nothing reads slipped, is taken to read
    // along unread. Before, it was read and the candidate turned away. No such candidate comes from the session
    @Test
    fun aWordOverKeysNothingReadsOtherwiseIsTakenToReadThereUnread() {
        // 你 (64) and 99, its second 9 tapped toward 6: a slip at the end only
        val keys = "6499"
        val touches = Array<Touch?>(keys.length) { TouchBoxes.tap(TouchBoxes.NINE, keys[it]) }
        touches[keys.length - 1] = TouchBoxes.tap(TouchBoxes.NINE, '9', dy = -0.42f)
        val graph = T9Segmenter(abbreviations = false, neighbours = true).segment(keys, null, touches)
        assertTrue("a slip read", graph.hasSlips && graph.readsSlipWithin(2, 4) && !graph.readsSlipWithin(0, 2))
        val whole = graph.withoutSlips()
        val ta = (0 until data.vocabulary.size).first { data.vocabulary.word(it) == "他" }
        // 他 (82) over 64
        val c = Candidate("他", 2, 0f, intArrayOf(ta), intArrayOf(2))
        assertEquals("walked, it does not read", false, walked(whole, c))
        val readings = HabitOrder.Readings()
        assertEquals("taken to read, unread", true, order.readsAlong(graph, whole, c, readings))
        assertEquals(0, readings.read)
    }

    // on the grouped keyboards nearly every place has a key read alone (061-sp-abbrev §3.1): those are no slips, so
    // with no tap's place no candidate is read at all, as before the keys alone were read there
    @Test
    fun keysReadAloneOnlyReadNoCandidate() {
        val letters = NineKeyLetters.NINE_SP
        // 小鹤 xm: 下面 by x and m alone, then 我们在家里 in whole syllables
        val keys = ("xm" + "womfzdjxli").map(letters::key).joinToString("")
        val graph = GroupedShuangpinSegmenter(ShuangpinScheme.XIAOHE, letters, neighbours = true).segment(keys, null, null)
        assertTrue("keys read alone", graph.hasAbbreviations && !graph.hasSlips)
        val xiaMian = (0 until data.vocabulary.size).first { data.vocabulary.word(it) == "下面" }
        // past text kept as typed a word is read anyway: none such here
        val candidates = candidates(graph).filter { NO_WORD !in it.words } + Candidate("下面", 2, 0f, intArrayOf(xiaMian), intArrayOf(2))
        assertTrue("${candidates.size} candidates", candidates.size > 5)
        assertTrue("下面 read by the keys alone", walked(graph, candidates.last()))
        val readings = HabitOrder.Readings()
        for (c in candidates) assertEquals(c.text, walked(graph, c), order.readsAlong(graph, graph.withoutSlips(), c, readings))
        assertEquals(0, readings.read)
    }
}
