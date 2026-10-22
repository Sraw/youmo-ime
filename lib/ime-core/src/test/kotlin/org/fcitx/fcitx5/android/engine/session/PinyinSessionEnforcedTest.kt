/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.session.Action.Context
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Pin
import org.fcitx.fcitx5.android.engine.session.Action.Reset
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the user's sentences keep (U-08, impl/design-1c.md §1): a first of theirs after its word, a
 * pin and a word added by hand put in the decoder's sentences; and the habits of a word of the
 * start ranked where it ends though the decoder's first word ends elsewhere (names:36).
 */
class PinyinSessionEnforcedTest {

    private val data = pinyinDataOf(
        Triple("我", "wo", -1f), Triple("喔", "wo", -4f), Triple("知道", "zhi dao", -1.5f), Triple("指导", "zhi dao", -3f),
        Triple("了", "le", -1.5f), Triple("老师", "lao shi", -2f), Triple("知道了", "zhi dao le", -2.5f),
        Triple("王", "wang", -2f), Triple("往", "wang", -2.5f), Triple("忘", "wang", -3f), Triple("汪", "wang", -4f),
        Triple("小明", "xiao ming", -2f), Triple("王小明", "wang xiao ming", -1.5f), Triple("吃饭", "chi fan", -2f),
        Triple("的", "de", -1f), Triple("得", "de", -3f), Triple("书", "shu", -2f),
    )
    private val learner = Learner(data)

    private fun session(user: UserModel = learner.model, added: Set<String> = emptySet()) =
        PinyinSession(data, PinyinSegmenter(), pageSize = 20, prediction = false, user = user, learnTo = learner.learnTo(), addedWords = { added })

    private val session = session()

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.lastOrNull() ?: apply(Action.Refine)

    private fun Session.pick(keys: String, text: String) {
        val shown = type(keys).candidates
        assertTrue("$text in $shown", text in shown)
        apply(Select(shown.indexOf(text)))
    }

    // sentence.txt: 我 → 指导 twice makes wozhidao 我指导, its score the decoder's
    @Test
    fun aFirstAfterItsWordIsKeptInSentences() {
        assertEquals("我知道", session.type("wozhidao").candidates.first())
        session.apply(Reset)
        repeat(2) {
            session.apply(Context("我"))
            session.pick("zhidao", "指导")
        }
        session.apply(Reset)
        assertEquals("我指导", session.type("wozhidao").candidates.first())
        session.apply(Reset)
        // after another word it is not theirs
        assertEquals("喔知道", session.type("wozhidao").candidates.firstOrNull { it.startsWith("喔") })
        session.apply(Reset)
        // 知道了 a word over zhidao and le: the sentence keeps it
        assertEquals("老师知道了", session.type("laoshizhidaole").candidates.first())
    }

    @Test
    fun aPinIsKeptInSentencesAfterAnyWord() {
        session.apply(Pin(session.type("zhidao").candidates.indexOf("指导")))
        session.apply(Reset)
        assertEquals("我指导", session.type("wozhidao").candidates.first())
        session.apply(Reset)
        assertEquals("喔指导", session.type("wozhidao").candidates.firstOrNull { it.startsWith("喔") })
    }

    // F2672: a separator typed is a place of no key; what is kept is kept all the same
    @Test
    fun aPinIsKeptInSentencesTypedWithASeparator() {
        session.apply(Pin(session.type("zhidao").candidates.indexOf("指导")))
        session.apply(Reset)
        assertEquals("我指导", session.type("wo'zhidao").candidates.first())
    }

    // a character pinned is first alone, the sentence's to read in a sentence (the user, 2026-10-21)
    @Test
    fun aCharacterPinnedIsNotKeptInSentences() {
        session.apply(Pin(session.type("de").candidates.indexOf("得")))
        session.apply(Reset)
        assertEquals("得", session.type("de").candidates.first())
        session.apply(Reset)
        assertEquals("我的书", session.type("wodeshu").candidates.first())
    }

    // review-design-1c S4: the sentences the pin makes the same are taken of more of the decoder's, as many as before
    @Test
    fun aPinKeepsAsManySentences() {
        val many = pinyinDataOf(
            Triple("我", "wo", -1f), Triple("喔", "wo", -2f), Triple("握", "wo", -2.2f), Triple("窝", "wo", -2.4f), Triple("沃", "wo", -2.6f),
            Triple("知道", "zhi dao", -1f), Triple("指导", "zhi dao", -1.5f), Triple("之道", "zhi dao", -1.6f),
        )
        val learner = Learner(many)
        val s = PinyinSession(many, PinyinSegmenter(), pageSize = 20, prediction = false, user = learner.model, learnTo = learner.learnTo())
        fun sentences() = s.type("wozhidao").candidates.filter { it.length == 3 }.also { s.apply(Reset) }
        val before = sentences()
        s.apply(Pin(s.type("zhidao").candidates.indexOf("指导")))
        s.apply(Reset)
        val pinned = sentences()
        assertEquals(pinned.toString(), before.size, pinned.size)
        assertTrue(pinned.toString(), pinned.all { it.endsWith("指导") })
    }

    // 汪小明 added by hand, the only word of wangxiaoming: kept in the sentence; 王小明 a word there too, not
    @Test
    fun aWordAddedByHandAloneOverItsKeysIsKept() {
        val alone = pinyinDataOf(Triple("王", "wang", -2f), Triple("汪", "wang", -4f), Triple("小明", "xiao ming", -2f), Triple("吃饭", "chi fan", -2f))
        val model = UserModel(alone.dictionary, alone.vocabulary, index = alone.wordIndex)
        model.listByHand(UserModel.Entry("汪小明", intArrayOf(Syllables.id("wang"), Syllables.id("xiao"), Syllables.id("ming"))))
        val learner = Learner(alone)
        val s = PinyinSession(alone, PinyinSegmenter(), pageSize = 20, prediction = false, user = model, learnTo = learner.learnTo(), addedWords = { setOf("汪小明") })
        assertEquals("汪小明吃饭", s.type("wangxiaomingchifan").candidates.first())
        s.apply(Reset)
        // its keys alone: that is the whole sentence (U-65), 汪 still to pick alone
        val itsKeys = s.type("wangxiaoming").candidates
        assertEquals("汪小明", itsKeys.first())
        assertTrue(itsKeys.toString(), "汪" in itsKeys)

        // a word of the dictionary's of the same keys: as U-02 has it, on the first page after the first, never kept in sentences
        val other = UserModel(data.dictionary, data.vocabulary, index = data.wordIndex)
        other.listByHand(UserModel.Entry("汪小明", intArrayOf(Syllables.id("wang"), Syllables.id("xiao"), Syllables.id("ming"))))
        assertEquals("王小明吃饭", session(other, setOf("汪小明")).type("wangxiaomingchifan").candidates.first())
        val same = session(other, setOf("汪小明")).type("wangxiaoming").candidates
        assertEquals(same.toString(), listOf("王小明", "汪小明"), same.take(2))
    }

    // names:36: 汪 picked under wang goes before 往 in wangxiaomingchifan, whose first word is 王小明
    @Test
    fun aWordOfTheStartUsedThereIsRankedWhereItEnds() {
        val fresh = session.type("wangxiaomingchifan").candidates
        assertTrue(fresh.toString(), fresh.indexOf("往") < fresh.indexOf("汪"))
        session.apply(Reset)
        session.pick("wangxiaoming", "汪")
        session.pick("", "小明")
        session.apply(Reset)
        val shown = session.type("wangxiaomingchifan").candidates
        assertTrue(shown.toString(), shown.indexOf("汪") in 0 until shown.indexOf("往"))
    }

    // the habit levels are worked out once a reading: a pause's refining re-places the sentences, never them
    @Test
    fun aPauseLeavesTheHabitLevelsAsTheyAre() {
        session.pick("wangxiaoming", "汪")
        session.pick("", "小明")
        session.apply(Reset)
        val refining = PinyinSession(
            data, PinyinSegmenter(), pageSize = 20, prediction = false, user = learner.model, learnTo = learner.learnTo(),
            refiner = { _, readings, _, _ -> readings.indexOfFirst { it.startsWith("往") } },
        )
        val typed = refining.type("wangxiaomingchifan").candidates
        val refined = refining.apply(Action.Refine).candidates
        assertTrue(refined.toString(), refined.indexOf("汪") in 0 until refined.indexOf("往"))
        assertEquals(typed.filter { it.length == 1 }, refined.filter { it.length == 1 })
    }

}
