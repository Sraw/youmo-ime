/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.ImportedWords
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.Word
import org.fcitx.fcitx5.android.engine.learn.Words
import org.fcitx.fcitx5.android.engine.learn.corrected
import org.fcitx.fcitx5.android.engine.learn.row
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** [HabitCounts]: the counts the decoder and 联想 go by, as the learning record has them (impl/design-1b.md §4). */
class HabitCountsTest {

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    // 了 at two readings, 解, 了解; 你, 好, 你好
    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("你", -2f, 0f)
                .unigram("好", -2.5f, 0f)
                .unigram("你好", -3f, 0f)
                .unigram("了", -2f, 0f)
                .unigram("解", -3f, 0f)
                .entry("你", syl("ni"))
                .entry("好", syl("hao"))
                .entry("你好", syl("ni", "hao"))
                .entry("了", syl("le"))
                .entry("了", syl("liao"))
                .entry("解", syl("jie"))
                .build()
                .toByteArray(),
        ),
    )

    private val learner = Learner(data)
    private val model get() = learner.model

    private fun count(text: String, vararg reading: String) = model.count(model.known(UserModel.Entry(text, syl(*reading))))

    private fun pairs(): Map<String, Float> = HashMap<String, Float>().apply {
        model.forEachCount({ _, _ -> }) { a, b, c -> put("${a.text}(${a.syllables.joinToString { Syllables.spelling(it) }}) ${b.text}", c) }
    }

    private fun commit(vararg rows: Row) = learner.habits.record(rows.toList())

    // NIT 4: on uncorrected single-piece commits the counts are the commits, as the old model's were under its halving
    @Test
    fun aWordsCountIsHowOftenItWasCommitted() {
        repeat(3) { commit(row("nihao", "你好", "ni hao")) }
        commit(corrected("hao", "好", "hao"))
        assertEquals(3f, count("你好", "ni", "hao"), 0f)
        assertEquals(1f, count("好", "hao"), 0f)
        assertEquals(0f, count("你", "ni"), 0f)
    }

    // M1: a word twice in a commit is used twice
    @Test
    fun aWordTwiceInACommitCountsTwice() {
        val haohao = Words(listOf(Word("好", "hao"), Word("好", "hao")))
        commit(Row(Row.PICK or Row.WHOLE, Code(Kinds.FULL, "haohao"), "", haohao, 'A', Row.DICTIONARY, ""))
        assertEquals(2f, count("好", "hao"), 0f)
        // and the pair of its two
        assertEquals(mapOf("好(hao) 好" to 1f), pairs())
    }

    @Test
    fun aPairIsHeldToTheCountOfTheWordBefore() {
        repeat(2) { commit(row("nihao", "你好", "ni hao")) }
        repeat(3) { commit(row("le", "了", "le", prev = "你好")) }
        assertEquals(mapOf("你好(ni, hao) 了" to 2f), pairs())
    }

    // S9: a word before, as the app's text gives it, is found by its text, whichever reading it was typed at
    @Test
    fun aWordBeforeIsFoundByItsText() {
        commit(row("le", "了", "le"))
        commit(row("liao", "了", "liao"))
        commit(row("jie", "解", "jie", prev = "了"))
        assertEquals(listOf(1f), pairs().filterKeys { it.startsWith("了") && it.endsWith(" 解") }.values.toList())
    }

    // D28: a table's or handwriting's word, of no reading, never counts for pinyin's order; a word
    // after it is still counted after it, for 联想, as many times as it was used
    @Test
    fun aWordOfNoReadingCountsNotForPinyinButWhatFollowsItDoes() {
        commit(row("wqvb", "你好", kind = Kinds.table("wubi")))
        commit(row("", "你好", kind = Kinds.HANDWRITING))
        assertEquals(0f, count("你好", "ni", "hao"), 0f)
        repeat(3) { commit(row("le", "了", "le", prev = "你好")) }
        val after = ArrayList<Int>()
        model.forEachAfter(model.idsOfText("你好").single()) { after += it }
        assertEquals(listOf(model.known(UserModel.Entry("了", syl("le")))), after)
    }

    // review C S2: a pair to a word put in by its text alone (a table's, handwriting's) is 联想's, never the decoder's
    @Test
    fun aPairToAWordOfNoReadingIsForPredictionAlone() {
        repeat(2) { commit(row("nihao", "你好", "ni hao")) }
        commit(row("le", "了", "le"))
        repeat(2) { commit(row("bnh", "了", kind = Kinds.table("wubi"), prev = "你好")) }
        val prev = model.known(UserModel.Entry("你好", syl("ni", "hao")))
        val next = model.known(UserModel.Entry("了", syl("le")))
        assertTrue(model.probability(prev, next) > model.decoderProbability(prev, next))
        // as if never typed after it, for the decoder
        val after = ArrayList<Int>()
        model.forEachAfter(prev) { after += it }
        assertEquals(listOf(next), after)
        assertEquals(emptyMap<String, Float>(), pairs())
    }

    private fun predicted(model: UserModel = this.model): Map<String, Float> = HashMap<String, Float>().apply {
        // the user's words made past the model's, by id: every one there is (review R11 F2813)
        fun text(id: Int) = if (id < data.vocabulary.size) data.vocabulary.word(id) else "#$id"
        for (prev in 0 until data.vocabulary.size + model.size) model.forEachPredicted(prev) { next, c -> put("${text(prev)} ${text(next)}", c) }
    }

    // U-71: 联想 counts what was put in after a word as often as it was, whatever the word before was used
    @Test
    fun aPairToPredictIsCountedAsPutInNotHeldToTheWordBefore() {
        commit(row("nihao", "你好", "ni hao"))
        // 你好 the app's text after the first: used once, followed three times
        repeat(3) { commit(row("le", "了", "le", prev = "你好")) }
        assertEquals(mapOf("你好(ni, hao) 了" to 1f), pairs())
        assertEquals(mapOf("你好 了" to 3f), predicted())
    }

    // U-71: one count for 联想 whatever the word was read as or put in by, the same made anew as
    // counted commit by commit (H review S5)
    @Test
    fun aPairToPredictIsSummedOverReadingsAsMakingThemAnewSumsIt() {
        commit(row("le", "了", "le", prev = "你好"))
        commit(row("liao", "了", "liao", prev = "你好"))
        repeat(2) { commit(row("bnh", "了", kind = Kinds.table("wubi"), prev = "你好")) }
        assertEquals(mapOf("你好 了" to 4f), predicted())
        HabitCounts(model).rebuild(learner.log.state)
        assertEquals(mapOf("你好 了" to 4f), predicted())
    }

    // U-91: a URL's or e-mail's field learns no pair, a search field's none for 联想 (H review M1)
    @Test
    fun aFieldThatLearnsNoPairsFeedsNoPrediction() {
        for (flags in listOf(Commit.NO_PAIRS, Commit.NO_PREDICT)) repeat(3) {
            learner.habits.record(listOf(row("le", "了", "le", prev = "你好")), flags = flags)
        }
        assertEquals(emptyMap<String, Float>(), predicted())
        assertEquals(emptyMap<String, Float>(), pairs())
    }

    // review-1b S1: 联想's counts too wait for a field to start on a new typing day, made then as anew
    @Test
    fun aPairToPredictOutOfTheWindowGoesAsAFieldStarts() {
        val learner = Learner(data, settles = false)
        repeat(3) { learner.habits.record(listOf(row("le", "了", "le", prev = "你好"))) }
        learner.clock.days(1)
        repeat(30) {
            learner.clock.days(1)
            learner.habits.record(listOf(row("ni", "你", "ni", prev = "好")))
        }
        assertTrue(learner.counts.stale)
        assertEquals(mapOf("你好 了" to 3f, "好 你" to 30f), predicted(learner.model))
        learner.counts.settle(learner.log.state)
        assertEquals(mapOf("好 你" to 30f), predicted(learner.model))
    }

    // U-71: a commit taken back (deleted) is no pair
    @Test
    fun aPairUndoneCountsNoMore() {
        val commits = List(3) { commit(row("le", "了", "le", prev = "你好")) }
        assertEquals(mapOf("你好 了" to 3f), predicted())
        learner.log.undo(commits.last()!!.seq)
        assertEquals(mapOf("你好 了" to 2f), predicted())
    }

    // review-C S1, S2: a word of no reading (a table's, handwriting's, 联想's) and the pairs to it are 联想's alone
    @Test
    fun aPredictionPickedIsCountedForPredictionAlone() {
        commit(row("nihao", "你好", "ni hao"))
        commit(Row(Row.PICK or Row.WHOLE, Code(Kinds.PREDICTION, "你好"), "你好", Words.of("了"), 'D', Row.DICTIONARY, "了"))
        // 联想's pick is no use of 了 the decoder counts
        assertEquals(0f, count("了", "le"), 0f)
        commit(row("le", "了", kind = Kinds.HANDWRITING, prev = "你好"))
        assertEquals(emptyMap<String, Float>(), pairs())
        assertEquals(mapOf("你好 了" to 2f), predicted())
        // forgotten, gone from both
        learner.habits.forget("了")
        assertEquals(emptyMap<String, Float>(), predicted())
    }

    @Test
    fun aWordForgottenCountsNoMore() {
        commit(row("nihao", "你好", "ni hao"))
        commit(row("le", "了", "le", prev = "你好"))
        learner.habits.forget("你好")
        assertEquals(0f, count("你好", "ni", "hao"), 0f)
        assertEquals(emptyMap<String, Float>(), pairs())
        assertEquals(1f, count("了", "le"), 0f)
    }

    // review R11 F2936: a text forgotten is forgotten at every reading it was typed at
    @Test
    fun aWordForgottenCountsNoMoreAtAnyReading() {
        commit(row("le", "了", "le"))
        commit(row("liao", "了", "liao"))
        commit(row("jie", "解", "jie", prev = "了"))
        learner.habits.forget("了")
        assertEquals(0f, count("了", "le"), 0f)
        assertEquals(0f, count("了", "liao"), 0f)
        assertEquals(emptyMap<String, Float>(), pairs())
        assertEquals(1f, count("解", "jie"), 0f)
    }

    // U-07: what was not used in the last 30 typing days counts no more; a calendar day with no typing is none
    @Test
    fun aWordUnusedForThirtyTypingDaysCountsNoMore() {
        commit(row("nihao", "你好", "ni hao"))
        learner.clock.days(100)
        repeat(29) {
            learner.clock.days(1)
            commit(row("hao", "好", "hao"))
        }
        assertEquals(1f, count("你好", "ni", "hao"), 0f)
        learner.clock.days(1)
        commit(row("hao", "好", "hao"))
        assertEquals(0f, count("你好", "ni", "hao"), 0f)
        assertEquals(30f, count("好", "hao"), 0f)
    }

    // M3: the user's own word stays theirs when its counts have gone
    @Test
    fun aWordOfTheUsersOwnIsTheirsUnused() {
        repeat(2) { commit(corrected("nijie", "你解", "ni jie", origin = Row.NONE)) }
        val id = model.known(UserModel.Entry("你解", syl("ni", "jie")))
        assertTrue(model.owns(id))
        learner.clock.days(1)
        repeat(30) {
            learner.clock.days(1)
            commit(row("hao", "好", "hao"))
        }
        assertEquals(0f, model.count(id), 0f)
        assertTrue(model.owns(model.known(UserModel.Entry("你解", syl("ni", "jie")))))
    }

    // review-1b S1: a word formed is counted, with the pairs it is in, as making every count anew counts it
    @Test
    fun aWordFormedIsCountedAsMakingThemAnewCountsIt() {
        commit(row("hao", "好", "hao"))
        commit(corrected("nijie", "你解", "ni jie", prev = "好", origin = Row.NONE))
        commit(row("hao", "好", "hao", prev = "你解"))
        commit(corrected("nijie", "你解", "ni jie", prev = "好", origin = Row.NONE))
        assertTrue(model.owns(model.known(UserModel.Entry("你解", syl("ni", "jie")))))
        val words = HashMap<String, Float>().apply { model.forEachWordCount { e, c -> put(e.toString(), c) } }
        val pairs = pairs()
        val predicted = predicted()
        assertEquals(2f, count("你解", "ni", "jie"), 0f)
        assertTrue(pairs.keys.any { it.startsWith("你解") } && pairs.keys.any { it.endsWith(" 你解") })
        HabitCounts(model).rebuild(learner.log.state)
        assertEquals(words, HashMap<String, Float>().apply { model.forEachWordCount { e, c -> put(e.toString(), c) } })
        assertEquals(pairs, pairs())
        assertEquals(predicted, predicted())
    }

    // review R11 F2659: a text found as no word while pending is found anew once imported, and typed
    @Test
    fun aWordImportedAfterItWasPendingIsCountedAsMakingThemAnewCountsIt() {
        // 好 used twice first: a pair after it is held to its count, which only a rebuild raises (committed)
        repeat(2) { commit(row("hao", "好", "hao")) }
        commit(corrected("nijie", "你解", "ni jie", prev = "好", origin = Row.NONE))
        learner.log.imports(ImportedWords.plan(1, "a.txt", 20000, listOf(ImportedWords.Incoming("你解", "ni jie"))) { _, _ -> false })
        commit(corrected("nijie", "你解", "ni jie", prev = "好", origin = Row.NONE))
        commit(row("hao", "好", "hao", prev = "你解"))
        val words = HashMap<String, Float>().apply { model.forEachWordCount { e, c -> put(e.toString(), c) } }
        val pairs = pairs()
        val predicted = predicted()
        HabitCounts(model).rebuild(learner.log.state)
        assertEquals(HashMap<String, Float>().apply { model.forEachWordCount { e, c -> put(e.toString(), c) } }, words)
        assertEquals(pairs(), pairs)
        assertEquals(predicted(), predicted)
    }

    // review-1b S1: a new typing day's counts wait for a field to start, a commit's words counted anew still
    @Test
    fun aNewTypingDaysCountsAreMadeAsAFieldStarts() {
        val learner = Learner(data, settles = false)
        val model = learner.model
        fun count(text: String, reading: String) = model.count(model.known(UserModel.Entry(text, syl(*reading.split(' ').toTypedArray()))))
        learner.habits.record(listOf(row("jie", "解", "jie")))
        learner.habits.record(listOf(row("hao", "好", "hao")))
        learner.clock.days(1)
        repeat(30) {
            learner.clock.days(1)
            learner.habits.record(listOf(row("ni", "你", "ni")))
        }
        // 解 out of the window, waiting; 你, committed, counted
        assertTrue(learner.counts.stale)
        assertEquals(1f, count("解", "jie"), 0f)
        assertEquals(30f, count("你", "ni"), 0f)
        learner.counts.settle(learner.log.state)
        assertEquals(0f, count("解", "jie"), 0f)
        assertEquals(30f, count("你", "ni"), 0f)
    }
}
