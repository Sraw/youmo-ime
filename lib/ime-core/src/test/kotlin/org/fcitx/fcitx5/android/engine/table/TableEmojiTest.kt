/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.handwriting.handwritingData
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Action.NextPage
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Test

/** A table's emoji candidates (EmojiCandidates): as pinyin's, and no 重码 of the code. */
class TableEmojiTest {

    private val table = dictionary("a 工", "a 戈", "aa 式", "aaa 🔧", "kkkk 叫", "wqvb 你好")

    private val emoji = mapOf("工" to listOf("🔧", "🛠️", "⚒️"), "叫" to listOf("📢"), "你好" to listOf("👋"))

    private fun session(emoji: Map<String, List<String>> = this.emoji, options: TableOptions = TableOptions.WUBI): TableSession {
        val d = handwritingData
        return TableSession(table, options, language = TableLanguage(TextWords(d.model, d.wordIndex), emoji = { emoji[it].orEmpty() }))
    }

    @Test
    fun theFirstsEmojiFollowTheSecondWithNoHint() {
        val s = session().type("a").second
        assertEquals(listOf("工", "戈", "🔧", "🛠️", "式"), s.candidates)
        assertEquals(listOf("", "", "", "", "a"), s.hints)
        // an entry of an emoji's text, listed once
        assertFalse("⚒️" in s.candidates)
        assertEquals(1, session().type("a").second.candidates.count { it == "🔧" })
        assertEquals(listOf("工", "戈", "式", "🔧"), session(emptyMap()).type("a").second.candidates)
    }

    @Test
    fun aLoneCandidateStillCommitsItselfTheEmojiAside() {
        val t = session()
        assertEquals("叫", t.type("kkkk").first)
    }

    @Test
    fun aWordThatCommittedItselfHasItsEmojiOfferedFirstAndOneTakesItsPlace() {
        val t = session()
        val (commit, s) = t.type("kkkk")
        assertEquals("叫", commit)
        assertTrue(s.predicting)
        assertEquals("📢", s.candidates.first())
        assertEquals(emptySet<Any>(), t.offers(0))
        val picked = t.apply(Select(0))
        assertEquals("📢", picked.commit)
        // the word deleted first: the emoji in its place
        assertEquals("叫".length, picked.deleteBefore)
        assertFalse(picked.predicting && "📢" in picked.candidates)
    }

    @Test
    fun anyOtherKeyEndsTheEmojiOfferAndGoesOn() {
        val t = session()
        t.type("kkkk")
        val (commit, s) = t.type("a")
        assertEquals("", commit)
        assertEquals("a", s.preedit)
        assertFalse("📢" in s.candidates)
        assertEquals(0, s.deleteBefore)
        // nothing to offer of a word with no emoji
        val u = session(emptyMap())
        assertFalse("📢" in u.type("kkkk").second.candidates)
    }

    @Test
    fun anEmojiIsPutInAsTextWithNothingToForgetOrBlock() {
        val t = session()
        val s = t.type("wqv").second
        assertEquals(listOf("你好", "👋"), s.candidates)
        assertEquals(emptySet<Any>(), t.offers(1))
        assertEquals("👋", t.apply(Select(1)).commit)
    }

    @Test
    fun aKeyEndingACodeOnAPageAnEmojiStartsCommitsTheFirstReadingNotTheEmoji() {
        val t = session(options = TableOptions.WUBI.copy(pageSize = 3))
        t.type("a")
        assertEquals("🛠️", t.apply(NextPage).candidates.first())
        // a comma, no code key, ends the code: what it commits, not the emoji first on the page
        assertEquals("工", t.type(",").first)
    }

    @Test
    fun aWordsBuiltInPhrasesComeBeforeItsEmojiInTheListAndInWhatFollowsALoneOne() {
        val phrases = mapOf("工" to listOf("2026年10月17日"), "叫" to listOf("09:05"))
        fun session() = TableSession(
            table, TableOptions.WUBI,
            language = TableLanguage(TextWords(handwritingData.model, handwritingData.wordIndex), emoji = { emoji[it].orEmpty() }, phrases = { phrases[it].orEmpty() }),
        )
        assertEquals(listOf("工", "戈", "2026年10月17日", "🔧", "🛠️"), session().type("a").second.candidates)
        val t = session()
        val (commit, s) = t.type("kkkk")
        assertEquals("叫", commit)
        assertEquals(listOf("09:05", "📢"), s.candidates.take(2))
        val picked = t.apply(Select(0))
        assertEquals("09:05", picked.commit)
        assertEquals(1, picked.deleteBefore)
    }

    // a lone word's emoji before what may follow (联想): 高 commits itself by g, then what follows 高
    private fun predicting(pageSize: Int = 2, emoji: List<String> = listOf("🗻", "📏"), block: ((UserModel.Entry) -> Unit)? = null): TableSession {
        val d = handwritingData
        val user = UserModel(d.dictionary, d.vocabulary)
        val follows = Predictor(d.model, d.vocabulary, d.dictionary, user)
        val language = TableLanguage(TextWords(d.model, d.wordIndex), user, predictor = follows, block = block, emoji = { if (it == "高") emoji else emptyList() })
        return TableSession(dictionary("g 高", "ab 人", "ab 山"), TableOptions.WUBI.copy(pageSize = pageSize), language = language)
    }

    @Test
    fun aPredictionPickedPastTheEmojiEndsTheirOffer() {
        val t = predicting()
        assertEquals("高", t.type("g").first)
        val all = t.candidates(0, 50).map { it.text }
        assertEquals(listOf("🗻", "📏"), all.take(2))
        assertTrue(all.size > 2)
        val picked = t.apply(Action.Pick(2))
        assertEquals(all[2], picked.commit)
        assertEquals(0, picked.deleteBefore)
        // what follows that is offered with no emoji of 高: none to replace a word no longer last
        val after = t.candidates(0, 50).map { it.text }
        assertFalse("🗻" in after || "📏" in after)
    }

    @Test
    fun aPickPastAllOfferedKeepsTheEmojiOffered() {
        val t = predicting()
        t.type("g")
        val all = t.candidates(0, 50).map { it.text }
        val none = t.apply(Action.Pick(all.size))
        assertEquals("", none.commit)
        assertEquals(0, none.deleteBefore)
        assertEquals(all, t.candidates(0, 50).map { it.text })
    }

    @Test
    fun pagesSelectsAndLongPressesCountTheEmojiFirst() {
        // one emoji, one a page: the prediction after it is on the second page, at index 1 of all
        fun session() = predicting(pageSize = 1, emoji = listOf("🗻"))
        val t = session()
        t.type("g")
        val all = t.candidates(0, 50).map { it.text }
        assertEquals("🗻", all[0])
        val follows = all[1]
        // the emoji offer nothing; the prediction what its own index offers
        val plain = predicting(pageSize = 1, emoji = emptyList())
        plain.type("g")
        assertEquals(emptySet<Any>(), t.offers(0))
        assertEquals(plain.offers(0), t.offers(1))
        // turning over the list as one
        assertEquals(listOf(follows), t.apply(NextPage).candidates)
        // a long press on it does to that prediction what it does without the emoji
        t.apply(Action.Forget(1))
        plain.apply(Action.Forget(0))
        assertEquals(plain.candidates(0, 50).map { it.text }, t.candidates(0, 50).map { it.text }.drop(1))
        // Select on the second page: page × size + index, the emoji counted
        val u = session()
        u.type("g")
        u.apply(NextPage)
        assertEquals(follows, u.apply(Select(0)).commit)
    }

    @Test
    fun anEmojiReplacingAWordPastTheBmpDeletesItsCodePoints() {
        // 𠀾 is one code point, two UTF-16 units: the app deletes code points
        val t = TableSession(
            dictionary("kkkk 𠀾𠀾"), TableOptions.WUBI,
            language = TableLanguage(TextWords(handwritingData.model, handwritingData.wordIndex), emoji = { if (it == "𠀾𠀾") listOf("📢") else emptyList() }),
        )
        assertEquals("𠀾𠀾", t.type("kkkk").first)
        assertEquals(2, t.apply(Select(0)).deleteBefore)
    }
}
