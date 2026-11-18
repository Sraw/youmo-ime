/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.learn.ImportedWords
import org.fcitx.fcitx5.android.engine.learn.import
import org.fcitx.fcitx5.android.engine.pinyin.GroupedShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.pinyin.TouchBoxes
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFile
import org.fcitx.fcitx5.android.engine.session.pinyinDataOf
import org.fcitx.fcitx5.android.engine.user.DictionaryCompiler
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 双拼's keys read alone where a key and the next make a syllable (简拼 in the middle) read only the
 * words the user typed or chose ([UserWords.typed]; DECISIONS 0.6.1 双拼中间简拼改 U), 自然码, on all four
 * keyboards (061-sp-abbrev §3.1); the arcs so read told apart from slips, and kept past the words' cap (§3.5).
 */
class PinyinDecoderTypedTest {

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    private val data = pinyinDataOf(
        Triple("你", "ni", -2f), Triple("拟", "ni", -5f), Triple("好", "hao", -2.5f), Triple("你好", "ni hao", -3f),
        Triple("张", "zhang", -3f), Triple("中", "zhong", -3.5f), Triple("中华", "zhong hua", -1.5f), Triple("人民", "ren min", -3f),
    )

    // the dictionaries the user merged: more of ni
    private fun mergedModel(): UserModel {
        val file = java.io.File.createTempFile("merged", ".ymd").apply { deleteOnExit() }
        DictionaryCompiler.compile(listOf(sequenceOf("妮 ni 0.5", "泥 ni 0.05", "尼 ni 0.005", "倪 ni 0", "你 ni 0.9")), file, inBase = DictionaryCompiler.inBase(data))
        return UserModel(data.dictionary, data.vocabulary, data.wordIndex, DictionaryFile.open(JvmFile(file)))
    }

    private val ziranma = ShuangpinSegmenter(ShuangpinScheme.ZIRANMA, abbreviations = true)

    // the words of the arcs over [keys], 自然码, from the start to the end
    private fun over(user: UserModel?, keys: String): List<String> {
        val graph = ziranma.segment(keys)
        val d = PinyinDecoder(data.dictionary, data.vocabulary, WordScorer.of(data.model), user = user)
        d.decode(graph)
        return d.arcsBetween(graph, 0, keys.length).map { it.text }
    }

    // DECISIONS 0.6.1 双拼中间简拼改 U: over a key read alone where it and the next make a syllable, only
    // the words the user typed or chose. nhk is n and hao (and nang, k): 你好 by n alone
    @Test
    @Uat("SP-45")
    fun aKeyReadAloneReadsOnlyTheWordsTypedOrChosen() {
        val nihao = UserModel.Entry("你好", syl("ni", "hao"))
        assertEquals(listOf("你好"), over(null, "nihk"))
        assertEquals(emptyList<String>(), over(null, "nhk"))
        val user = UserModel(data.dictionary, data.vocabulary, data.wordIndex)
        assertEquals(emptyList<String>(), over(user, "nhk"))
        // counted: typed
        user.setCount(user.id(nihao), 1f, nihao)
        assertEquals(listOf("你好"), over(user, "nhk"))
        // and no other word: 中华 by zh alone and hua is none (vh is zhang)
        assertEquals(emptyList<String>(), over(user, "vhw"))
        // a key that makes no syllable with the next is an initial as it ever was: rm is none, 人民 by r and min
        assertEquals(listOf("人民"), over(user, "rmn"))
        user.setCount(user.id(nihao), 0f, nihao)
        assertEquals(emptyList<String>(), over(user, "nhk"))
        // the user's own: chosen, with no count
        user.own(nihao)
        assertEquals(listOf("你好"), over(user, "nhk"))
        user.disown(nihao)
        assertEquals(emptyList<String>(), over(user, "nhk"))
        // the dictionary's own word added by hand: the user named it, though its reading and score stay the dictionary's
        user.listByHand(nihao)
        assertTrue(!user.chosen(user.id(nihao)))
        assertEquals(listOf("你好"), over(user, "nhk"))
        user.unlistByHand(nihao)
        assertEquals(emptyList<String>(), over(user, "nhk"))
        // one the dictionary lacks, made by the user
        val nizhong = UserModel.Entry("你中", syl("ni", "zhong"))
        user.own(nizhong)
        assertEquals(listOf("你中"), over(user, "nvs"))
        // in whole syllables every word is read, typed or not
        assertEquals(listOf("人民"), over(user, "rfmn"))
    }

    // the same of the dictionaries the user merged and of the words only imported: none over a key
    // read alone till typed or chosen, all of them in whole syllables
    @Test
    @Uat("SP-45")
    fun aKeyReadAloneReadsNoMergedOrImportedWordNeverTyped() {
        val user = mergedModel()
        user.attachImports(ImportedWords().import("妮好" to "ni hao"))
        // nhk from the start: n alone is 妮, 泥, 尼, 倪 of the merged file and 你, 拟; 妮好 imported
        fun alone(): List<String> {
            val graph = ziranma.segment("nhk")
            val d = PinyinDecoder(data.dictionary, data.vocabulary, UserScorer(data.model, user), user = user)
            d.decode(graph)
            return d.arcsBetween(graph, 0, 1).map { it.text } + d.arcsBetween(graph, 0, 3).map { it.text }
        }
        assertEquals(emptyList<String>(), alone())
        assertTrue(over(user, "ni").containsAll(listOf("妮", "泥", "你")))
        assertTrue("妮好" in over(user, "nihk"))
        val ni = UserModel.Entry("泥", syl("ni"))
        user.setCount(user.id(ni), 2f, ni)
        assertEquals(listOf("泥"), alone())
        // added by hand from the merged dictionary: chosen
        user.listByHand(UserModel.Entry("倪", syl("ni")))
        assertEquals(setOf("泥", "倪"), alone().toSet())
        // the imported one, made the user's own
        user.own(UserModel.Entry("妮好", syl("ni", "hao")))
        assertEquals(setOf("泥", "倪", "妮好"), alone().toSet())
    }

    // the 18, 14 and nine keys read keys alone as 26 keys do: the words typed only
    @Test
    @Uat("SP-52", "SP-53", "SP-54")
    fun theGroupedKeyboardsReadOnlyTheWordsTypedByAKeyAlone() {
        val nihao = UserModel.Entry("你好", syl("ni", "hao"))
        for (letters in listOf(NineKeyLetters.G18, NineKeyLetters.G14, NineKeyLetters.NINE_SP)) {
            val segmenter = GroupedShuangpinSegmenter(ShuangpinScheme.ZIRANMA, letters)
            fun over(user: UserModel?, keys: String): List<String> {
                val graph = segmenter.segment(keys.map(letters::key).joinToString(""))
                val d = PinyinDecoder(data.dictionary, data.vocabulary, WordScorer.of(data.model), user = user)
                d.decode(graph)
                return d.arcsBetween(graph, 0, keys.length).map { it.text }
            }
            assertTrue("$letters", "你好" in over(null, "nihk"))
            assertTrue("$letters", "你好" !in over(null, "nhk"))
            val user = UserModel(data.dictionary, data.vocabulary, data.wordIndex)
            assertTrue("$letters", "你好" !in over(user, "nhk"))
            user.setCount(user.id(nihao), 1f, nihao)
            assertTrue("$letters", "你好" in over(user, "nhk"))
            // n and h each alone too
            assertTrue("$letters", "你好" in over(user, "nh"))
        }
    }

    // ky: kuai, more of it than the words' cap, below them 可以 by k and y alone; y tapped by u is ku too, 苦 by the slip
    private val many = pinyinDataOf(
        *(0 until 70).map { Triple(('\u4e00' + 200 + it).toString(), "kuai", -2f - it * 0.01f) }.toTypedArray(),
        Triple("可以", "ke yi", -9f), Triple("苦", "ku", -9f), Triple("你", "ni", -2f),
    )

    private fun typed(vararg words: Pair<String, String>) = UserModel(many.dictionary, many.vocabulary, many.wordIndex).also { user ->
        for ((text, reading) in words) {
            val e = UserModel.Entry(text, reading.split(' ').map(Syllables::id).toIntArray())
            if (user.id(e) < 0) user.own(e) else user.setCount(user.id(e), 1f, e)
        }
    }

    private fun slipped(keys: String, at: Int): Array<Touch?> =
        Array(keys.length) { if (it == at) TouchBoxes.tap(TouchBoxes.QWERTY, keys[it], dx = 0.45f) else TouchBoxes.tap(TouchBoxes.QWERTY, keys[it]) }

    private val neighbours = ShuangpinSegmenter(ShuangpinScheme.ZIRANMA, neighbours = true)

    // an arc over a key alone is told from one over a slip (review r2 M1): held back where slips are, only the slipped
    @Test
    fun arcsOverAKeyAloneAreHeldBackOnlyWhereTheyAreSlippedToo() {
        val user = typed("可以" to "ke yi", "苦" to "ku", "你快" to "ni kuai", "你苦" to "ni ku")
        val d = PinyinDecoder(many.dictionary, many.vocabulary, UserScorer(many.model, user), user = user)
        fun between(graph: SyllableGraph, to: Int, holdsSlips: Boolean) = d.arcsBetween(graph, 0, to, holdsSlips).map { it.text }.toSet()
        // ky, y by u: 可以 alone, 苦 slipped
        val ky = neighbours.segment("ky", null, slipped("ky", 1))
        d.decode(ky)
        assertTrue(between(ky, 2, false).containsAll(listOf("可以", "苦")))
        assertTrue("可以" in between(ky, 2, true))
        assertFalse("苦" in between(ky, 2, true))
        // nky: n alone, then ky; 你快 alone, 你苦 alone and slipped
        val nky = neighbours.segment("nky", null, slipped("nky", 2))
        d.decode(nky)
        assertTrue(between(nky, 3, false).containsAll(listOf("你快", "你苦")))
        assertTrue("你快" in between(nky, 3, true))
        assertFalse("你苦" in between(nky, 3, true))
    }

    // past the words' cap the typed words read alone to the end still come (§3.5), not one read over a slip
    @Test
    @Uat("SP-57")
    fun wordsTypedReadAloneAreKeptPastTheCapButNotOnesSlipped() {
        val user = typed("可以" to "ke yi", "苦" to "ku")
        val d = PinyinDecoder(many.dictionary, many.vocabulary, UserScorer(many.model, user), user = user)
        val graph = neighbours.segment("ky", null, slipped("ky", 1))
        val words = d.decode(graph).words.map { it.text }
        assertTrue("${words.size}", words.size > PinyinDecoder.DEFAULT_WORDS)
        assertTrue("可以" in words)
        assertTrue("苦" in d.arcsBetween(graph, 0, 2).map { it.text })
        assertFalse("苦" in words)
    }

    // ky past the cap again, nine typed words read by k alone and y begun, more than the slots; 可 typed, by k alone, ends before
    private val slotted = (0 until 70).map { Triple(('\u4e00' + 200 + it).toString(), "kuai", -2f - it * 0.01f) } +
        listOf("可以" to "ke yi", "开眼" to "kai yan", "考研" to "kao yan", "苦于" to "ku yu", "恐有" to "kong you", "看样" to "kan yang", "可用" to "ke yong", "快要" to "kuai yao", "跨越" to "kua yue")
            .mapIndexed { i, (text, reading) -> Triple(text, reading, -9f - i * 0.1f) } + Triple("可", "ke", -5f)

    // the slots right after the first 64 words, as many as there are (R27 F8561), whatever the words asked: a list read on
    // with more is the shorter one and more past it (R27 F8537). The grouped keyboards (not wholeFirst) look past a word
    // that does not read all the keys (review NIT-1, R27 F8557): 可 ranks above the typed words there
    @Test
    fun theTypedWordsSlotsStayRightAfterTheFirstWordsWhateverIsAsked() {
        val d = pinyinDataOf(*slotted.toTypedArray())
        val user = UserModel(d.dictionary, d.vocabulary, d.wordIndex)
        val typed = slotted.drop(70).map { it.first }
        for ((text, reading) in slotted.drop(70).map { it.first to it.second }) {
            val e = UserModel.Entry(text, reading.split(' ').map(Syllables::id).toIntArray())
            user.setCount(user.id(e), 1f, e)
        }
        val g18 = NineKeyLetters.G18
        val keyboards = listOf(
            Triple("26", ziranma.segment("ky"), true),
            Triple("$g18", GroupedShuangpinSegmenter(ShuangpinScheme.ZIRANMA, g18).segment("ky".map(g18::key).joinToString("")), false),
        )
        for ((name, graph, wholeFirst) in keyboards) {
            val dec = PinyinDecoder(d.dictionary, d.vocabulary, WordScorer.of(d.model), user = user, wholeFirst = wholeFirst)
            val first = dec.decode(graph).words.map { it.text }
            assertEquals(name, PinyinDecoder.DEFAULT_WORDS + PinyinDecoder.TYPED_SLOTS, first.size)
            assertEquals("$name $first", PinyinDecoder.TYPED_SLOTS, first.count { it in typed })
            assertEquals("$name $first", typed.take(PinyinDecoder.TYPED_SLOTS), first.drop(PinyinDecoder.DEFAULT_WORDS))
            assertFalse(name, "可" in first)
            val more = dec.words(graph, words = 2 * PinyinDecoder.DEFAULT_WORDS).map { it.text }
            assertTrue("$name ${more.size}", more.size > first.size && "可" in more && typed.last() in more)
            assertEquals(name, first, more.take(first.size))
        }
    }

    // a decoder's every reading: its sentences, its words and the arcs between every two positions, slips held or not
    private fun lattice(d: PinyinDecoder, graph: SyllableGraph): List<String> {
        val r = d.decode(graph)
        return (r.sentences + r.words).map { "${it.text} ${it.score} ${it.ends.toList()}" } + (graph.start until graph.end).flatMap { a ->
            (a + 1..graph.end).flatMap { b -> listOf(false, true).flatMap { h -> d.arcsBetween(graph, a, b, h).map { "$a-$b $h ${it.text} ${it.score}" } } }
        }
    }

    // §3.9: over keys alone only the children with a typed word below are walked; they read what looking each
    // match up reads (collectMatches, the decoder's way before; code review SHOULD-3: not the walk against itself)
    @Test
    fun walkingOnlyTheChildrenWithATypedWordBelowReadsTheSame() {
        val pieces = listOf("ni", "hk", "vs", "hw", "rf", "mn", "n", "h", "v", "r", "m", "k")
        val keyboards: List<Pair<String, (String) -> String>> = listOf("26" to { k: String -> k }) +
            listOf(NineKeyLetters.G18, NineKeyLetters.G14, NineKeyLetters.NINE_SP).map { l -> l.toString() to { k: String -> k.map(l::key).joinToString("") } }
        val segmenters: List<Segmenter> = listOf(ziranma) +
            listOf(NineKeyLetters.G18, NineKeyLetters.G14, NineKeyLetters.NINE_SP).map { GroupedShuangpinSegmenter(ShuangpinScheme.ZIRANMA, it) }
        for (own in listOf(false, true)) {
            val user = UserModel(data.dictionary, data.vocabulary, data.wordIndex)
            for ((text, reading) in listOf("你好" to "ni hao", "中华" to "zhong hua", "人民" to "ren min", "你" to "ni")) {
                val e = UserModel.Entry(text, reading.split(' ').map(Syllables::id).toIntArray())
                user.setCount(user.id(e), 1f, e)
            }
            // the user's own trie has children: those steps go by the match
            if (own) user.own(UserModel.Entry("你中", syl("ni", "zhong")))
            val random = Random(39)
            var read = 0
            for ((k, segmenter) in segmenters.withIndex()) {
                val (name, code) = keyboards[k]
                val typedWords = listOf("你好", "中华", "人民", "你中")
                repeat(150) {
                    val keys = (1..random.nextInt(1, 5)).joinToString("") { pieces.random(random) }
                    val graph = segmenter.segment(code(keys))
                    val fast = PinyinDecoder(data.dictionary, data.vocabulary, UserScorer(data.model, user), user = user)
                    val each = PinyinDecoder(data.dictionary, data.vocabulary, UserScorer(data.model, user), user = user, walksTyped = false)
                    val a = lattice(fast, graph)
                    assertEquals("$name $own $keys", lattice(each, graph), a)
                    if (graph.hasAbbreviations && a.any { c -> typedWords.any { it in c } }) read++
                }
            }
            assertTrue("$own: $read read over keys alone", read > 100)
        }
    }
}
