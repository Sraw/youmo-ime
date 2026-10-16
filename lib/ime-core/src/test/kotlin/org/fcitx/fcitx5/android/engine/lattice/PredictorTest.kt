/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.log10
import kotlin.math.pow

class PredictorTest {

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("你", -2f, 0f)
                .unigram("好", -2.5f, 0f)
                .unigram("，", -1.5f, 0f)
                .unigram("吗", -3.5f, 0f)
                .unigram("我", -2f, 0f)
                .unigram("再", -2.5f, 0f)
                .unigram("见", -3f, 0f)
                .unigram("A股", -4f, 0f)
                .unigram("马克·吐温", -5f, 0f)
                .unigram("·", -3f, 0f)
                .unigram("二〇二六", -5f, 0f)
                .bigram("你", "好", -0.75f, 0f)
                .bigram("你", "，", -0.25f, 0f)
                .bigram("你", "吗", -1.5f, 0f)
                .bigram("你", "<unk>", -1f, 0f)
                .bigram("你", "A股", -2f, 0f)
                .bigram("你", "马克·吐温", -3f, 0f)
                .bigram("你", "·", -0.5f, 0f)
                .bigram("好", "二〇二六", -1f, 0f)
                .bigram("吗", "好", -1f, 0f)
                .bigram("吗", "你", -1f, 0f)
                .bigram("我", "再", -0.5f, -0.5f)
                .bigram("再", "见", -0.5f, 0f)
                .bigram("再", "吗", -1f, 0f)
                .bigram("见", "，", -0.5f, 0f)
                .trigram("我", "再", "见", -0.25f)
                .unigram("再见", -4f, 0f)
                .entry("你", intArrayOf(Syllables.id("ni")))
                .entry("我", intArrayOf(Syllables.id("wo")))
                .entry("见", intArrayOf(Syllables.id("jian")))
                .entry("见", intArrayOf(Syllables.id("xian")))
                .entry("再", intArrayOf(Syllables.id("zai")))
                .entry("再见", intArrayOf(Syllables.id("zai"), Syllables.id("jian")))
                .build().toByteArray(),
        ),
    )

    private val predictor = Predictor(data.model, data.vocabulary, data.dictionary)

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    private fun id(word: String) = (0 until data.vocabulary.size).first { data.vocabulary.word(it) == word }

    private fun texts(c: List<Candidate>) = c.map { it.text }

    @Test
    fun theWordsSeenAfterTheLastOneComeMostProbableFirst() {
        val after = predictor.predict(NO_WORD, id("你"))
        // ， · and <unk> are more probable, and no words
        assertEquals(listOf("好", "吗", "A股", "马克·吐温"), texts(after))
        assertEquals(listOf("二〇二六"), texts(predictor.predict(NO_WORD, id("好"))))
        val context = data.model.context(NO_WORD, id("你"))
        for (c in after) {
            assertEquals(data.model.scoreAfter(context, c.words.single()), c.score, 0f)
            assertEquals(0, c.end)
        }
    }

    @Test
    fun eachWordSeenAfterTheLastOneIsLookedAtOnce() {
        val looked = ArrayList<Int>()
        val user = object : UserWords {
            override fun child(node: Int, syllable: Int) = -1
            override fun childCount(node: Int) = 0
            override fun wordCount(node: Int) = 0
            override fun word(node: Int, index: Int) = NO_WORD
            override fun text(word: Int) = ""
            override fun blockedAnyhow(word: Int) = false.also { looked += word }
        }
        val after = Predictor(data.model, data.vocabulary, data.dictionary, user).predict(NO_WORD, id("你"))
        assertEquals(listOf("好", "吗", "A股", "马克·吐温"), texts(after))
        // 好 among them, the first offered, which also tells whether any word is: the old loop looked at it twice
        assertEquals(looked.distinct(), looked)
        assertTrue(id("好") in looked)
    }

    @Test
    fun theWordBeforeThatCounts() {
        val after = predictor.predict(id("我"), id("再"))
        assertEquals(listOf("见", "吗"), texts(after))
        assertEquals(-0.25f, after[0].score, 0f)
        // 吗 was never seen after 我 再: the backoff of 我 再 on top
        assertEquals(-0.5f - 1f, after[1].score, 1e-6f)
    }

    @Test
    fun theLimitIsKept() {
        assertEquals(listOf("好"), texts(predictor.predict(NO_WORD, id("你"), limit = 1)))
        assertEquals(listOf("好", "吗"), texts(predictor.predict(NO_WORD, id("你"), limit = 2)))
        assertTrue(predictor.predict(NO_WORD, id("你"), limit = 0).isEmpty())
    }

    @Test
    fun aTieGoesToTheWordSeenFirst() {
        // 你 comes before 好 in the vocabulary, and both follow 吗 at -1
        assertEquals(listOf("你"), texts(predictor.predict(NO_WORD, id("吗"), limit = 1)))
        assertEquals(listOf("你", "好"), texts(predictor.predict(NO_WORD, id("吗"))))
    }

    @Test
    fun nothingIsPredictedWithoutAWordOrAfterOneNeverFollowed() {
        assertTrue(predictor.predict(NO_WORD, NO_WORD).isEmpty())
        assertTrue(predictor.predict(id("你"), NO_WORD).isEmpty())
        assertTrue(predictor.predict(NO_WORD, id("见")).isEmpty())
    }

    @Test
    fun anUnseenWordEndsInItsLastTwoCharactersOrItsLast() {
        assertEquals(id("再见"), predictor.tail("说再见", syl("shuo", "zai", "jian")))
        assertEquals(id("见"), predictor.tail("看见", syl("kan", "jian")))
        // the end read otherwise is not the model's word
        assertEquals(NO_WORD, predictor.tail("说再见", syl("shuo", "zai", "jie")))
        // read as the model's word but written otherwise: the last character alone
        assertEquals(id("见"), predictor.tail("说在见", syl("shuo", "zai", "jian")))
        // a word no longer than its end is itself; characters and syllables that do not pair up
        assertEquals(NO_WORD, predictor.tail("见", syl("jian")))
        assertEquals(NO_WORD, predictor.tail("说再见", syl("zai", "jian")))
        assertEquals(NO_WORD, Predictor(data.model, data.vocabulary).tail("看见", syl("kan", "jian")))
        assertEquals(listOf("见", "吗"), texts(predictor.predict(NO_WORD, predictor.tail("说再", syl("shuo", "zai")))))
    }

    @Test
    fun whatTheUserTypedAfterTheWordComesFirst() {
        val user = UserModel(data.dictionary, data.vocabulary)
        val predicting = Predictor(data.model, data.vocabulary, data.dictionary, user)
        val wo = Entry("我", syl("wo"))
        assertEquals("再", texts(predicting.predict(NO_WORD, id("我"))).first())
        // 见 after 我, which the model never saw: once puts it second, three times before 再 at -0.5
        user.learn(null, listOf(wo, Entry("见", syl("jian"))))
        assertEquals(listOf("再", "见"), texts(predicting.predict(NO_WORD, id("我"))).take(2))
        repeat(2) { user.learn(null, listOf(wo, Entry("见", syl("jian")))) }
        assertEquals(listOf("见", "再"), texts(predicting.predict(NO_WORD, id("我"))).take(2))
        // a word the user made, after another and before another
        user.learn(wo, listOf(Entry("见见", syl("jian", "jian"))))
        val own = user.id(Entry("见见", syl("jian", "jian")))
        assertTrue("见见" in texts(predicting.predict(NO_WORD, id("我"))))
        user.learn(Entry("见见", syl("jian", "jian")), listOf(Entry("你", syl("ni"))))
        assertEquals(listOf("你"), texts(predicting.predict(NO_WORD, own)))
        // with what the model has after the end of it, as likely as each is
        assertEquals(listOf("见", "你", "吗"), texts(predicting.predict(NO_WORD, own) { id("再") }))
        // blocked, it is offered no more
        user.block(Entry("见", syl("jian")))
        assertTrue("见" !in texts(predicting.predict(NO_WORD, id("我"))))
    }

    @Test
    fun aWordOfTheUsersChosenAtAReadingOfTheirOwnIsTheModelsWordAsContext() {
        val user = UserModel(data.dictionary, data.vocabulary, index = data.wordIndex)
        val predicting = Predictor(data.model, data.vocabulary, data.dictionary, user)
        // added by hand at readings the dictionary lacks: what follows 你, and 我 再 as the context of 见
        val nin = user.listByHand(Entry("你", syl("nin")))
        val o = user.listByHand(Entry("我", syl("o")))
        assertTrue(nin >= data.vocabulary.size && o >= data.vocabulary.size)
        assertEquals(texts(predictor.predict(NO_WORD, id("你"))), texts(predicting.predict(NO_WORD, nin)))
        val trigram = predictor.predict(id("我"), id("再"))
        assertEquals(trigram.map { it.text to it.score }, predicting.predict(o, id("再")).map { it.text to it.score })
        // only imported, never picked: not the model's 你 yet, so nothing follows it
        val nian = user.list(Entry("你", syl("nian")))
        assertEquals(emptyList<String>(), texts(predicting.predict(NO_WORD, nian)))
    }

    @Test
    fun aWordForgottenIsReadAsTheDictionaryReadsItNotAsItWasLearned() {
        val user = UserModel(data.dictionary, data.vocabulary)
        val predicting = Predictor(data.model, data.vocabulary, data.dictionary, user)
        val xian = Entry("见", syl("xian"))
        user.learn(null, listOf(xian))
        assertEquals(syl("xian").toList(), predicting.reading(id("见"))?.toList())
        // picked from 联想 after, it is learned at the dictionary's reading, not the one forgotten
        user.forget(listOf(xian))
        assertEquals(predictor.reading(id("见"))?.toList(), predicting.reading(id("见"))?.toList())
        assertEquals(syl("jian").toList(), predicting.reading(id("见"))?.toList())
    }

    @Test
    fun aWordIsReadAsTheDictionaryReadsItBest() {
        assertEquals(syl("zai", "jian").toList(), predictor.reading(id("再见"))?.toList())
        assertEquals(syl("ni").toList(), predictor.reading(id("你"))?.toList())
        // no reading of its characters is the dictionary's word
        assertEquals(null, predictor.reading(id("吗")))
        assertEquals(null, Predictor(data.model, data.vocabulary).reading(id("你")))
        val user = UserModel(data.dictionary, data.vocabulary)
        val own = user.learn(null, listOf(Entry("见你", syl("jian", "ni")))).single()
        val predicting = Predictor(data.model, data.vocabulary, data.dictionary, user)
        assertEquals(syl("jian", "ni").toList(), predicting.reading(own)?.toList())
        // one the user typed is read as they typed it, 见 as xian
        user.learn(null, listOf(Entry("见", syl("xian"))))
        assertEquals(syl("xian").toList(), predicting.reading(id("见"))?.toList())
    }

    @Test
    fun theCharacterReadingsGivenAreAskedForOnceWhenFirstNeeded() {
        val readings = Predictor.charReadingsOf(data.dictionary, data.vocabulary)
        assertEquals(setOf("jian", "xian"), readings.getValue('见'.code).map { Syllables.spelling(it) }.toSet())
        var asked = 0
        val shared = Predictor(data.model, data.vocabulary, data.dictionary, charReadings = { readings.also { asked++ } })
        shared.predict(NO_WORD, id("你"))
        assertEquals(0, asked)
        assertEquals(syl("zai", "jian").toList(), shared.reading(id("再见"))?.toList())
        assertEquals(syl("ni").toList(), shared.reading(id("你"))?.toList())
        assertEquals(1, asked)
        // what they say goes: with 见 read as xian alone, 再见 has no reading of the dictionary's
        val xian = readings + ('见'.code to syl("xian"))
        assertEquals(null, Predictor(data.model, data.vocabulary, data.dictionary, charReadings = { xian }).reading(id("再见")))
    }

    @Test
    fun aWordWhoseFollowersAreAllLeftOutGoesOnFromItsEnd() {
        val user = UserModel(data.dictionary, data.vocabulary)
        val predicting = Predictor(data.model, data.vocabulary, data.dictionary, user)
        // 见 has only punctuation after it
        assertEquals(listOf("再"), texts(predicting.predict(NO_WORD, id("见")) { id("我") }))
        // 再 only what the user blocked
        user.block(Entry("见", syl("jian")))
        assertEquals(listOf("吗"), texts(predicting.predict(NO_WORD, id("再")) { id("我") }))
    }

    @Test
    fun whatTheUserTypedOfAModelsWordAtAReadingOfTheirOwnAddsToTheModelsProbability() {
        val user = UserModel(data.dictionary, data.vocabulary, index = data.wordIndex)
        val predicting = Predictor(data.model, data.vocabulary, data.dictionary, user, userWeight = 0.01f)
        // 再 typed after 我 at a reading the dictionary lacks: the model's 再, its -0.5 there and the user's share summed
        val ce = user.learn(null, listOf(Entry("我", syl("wo")), Entry("再", syl("ce"))))[1]
        assertTrue(ce >= data.vocabulary.size)
        val model = predictor.predict(NO_WORD, id("我")).first { it.text == "再" }.score
        val expected = log10(10f.pow(model) + 0.01f * user.probability(id("我"), ce))
        assertEquals(expected, predicting.predict(NO_WORD, id("我")).first { it.text == "再" }.score, 1e-6f)
    }

    @Test
    fun aTextIsOfferedOnceThoughTheUserReadItOtherwise() {
        val user = UserModel(data.dictionary, data.vocabulary)
        val predicting = Predictor(data.model, data.vocabulary, data.dictionary, user)
        user.learn(null, listOf(Entry("我", syl("wo")), Entry("再", syl("ce"))))
        assertEquals(listOf("再"), texts(predicting.predict(NO_WORD, id("我"))))
    }

    @Test
    fun aTextTheUserTypedAtAReadingOfTheirOwnTakesOnePlace() {
        val user = UserModel(data.dictionary, data.vocabulary, index = data.wordIndex)
        val predicting = Predictor(data.model, data.vocabulary, data.dictionary, user)
        // 好 typed after 你 at a reading the dictionary lacks: the model's 好 too, yet one place of two
        val hao = user.learn(null, listOf(Entry("你", syl("ni")), Entry("好", syl("hao"))))[1]
        assertTrue(hao >= data.vocabulary.size)
        assertEquals(listOf("好", "吗"), texts(predicting.predict(NO_WORD, id("你"), limit = 2)))
    }

    // typed at two readings of the user's, one text takes one place, as the more typed of them,
    // whichever is offered first (the other then drops, or is replaced)
    @Test
    fun aTextTypedAtTwoReadingsTakesOnePlaceAsTheMoreTyped() {
        val user = UserModel(data.dictionary, data.vocabulary, index = data.wordIndex)
        val predicting = Predictor(data.model, data.vocabulary, data.dictionary, user)
        val hao = (1..2).map { user.learn(null, listOf(Entry("你", syl("ni")), Entry("好", syl("hao"))))[1] }.last()
        user.learn(null, listOf(Entry("你", syl("ni")), Entry("好", syl("hai"))))
        val offered = predicting.predict(NO_WORD, id("你"), limit = 2)
        assertEquals(listOf("好", "吗"), texts(offered))
        assertEquals(hao, offered[0].words.single())
    }

    /** 小穴 was predicted after 妈妈的. */
    @Test
    fun aCoarseWordIsNeverPredicted() {
        val coarse = CoarseWords.of({ data.wordIndex.find(it) }, listOf("好"))
        val after = Predictor(data.model, data.vocabulary, data.dictionary, coarse = coarse).predict(NO_WORD, id("你"))
        assertEquals(listOf("吗", "A股", "马克·吐温"), texts(after))
    }

    // typed once, it is the user's: predicted as any other
    @Test
    fun aCoarseWordTheUserTypedIsPredicted() {
        val user = UserModel(data.dictionary, data.vocabulary)
        val coarse = CoarseWords.of({ data.wordIndex.find(it) }, listOf("见"))
        val predicting = Predictor(data.model, data.vocabulary, data.dictionary, user, coarse = coarse)
        assertEquals(listOf("吗"), texts(predicting.predict(NO_WORD, id("再"))))
        // after no word: 见 after 再 is the model's alone
        user.learn(null, listOf(Entry("见", syl("jian"))))
        assertEquals(listOf("见", "吗"), texts(predicting.predict(NO_WORD, id("再"))))
    }
}
