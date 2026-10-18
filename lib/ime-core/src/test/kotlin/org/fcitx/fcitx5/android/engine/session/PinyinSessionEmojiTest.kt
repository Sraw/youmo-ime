/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** A pinyin session's emoji candidates (EmojiCandidates). */
class PinyinSessionEmojiTest {

    private val data = sessionTestData()

    private val emoji = mapOf("你好" to listOf("👋", "🙋", "🤝"), "你" to listOf("🫵"))

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    @Test
    fun theFirstReadingsEmojiFollowTheSecondAndArePutInAsText() {
        val user = UserModel(data.dictionary, data.vocabulary)
        val session = PinyinSession(data, PinyinSegmenter(), pageSize = 20, user = user, emoji = { emoji[it].orEmpty() })
        val s = session.type("nihao")
        assertEquals("你好", s.candidates[0])
        assertEquals(listOf("👋", "🙋"), s.candidates.subList(2, 4))
        assertFalse("🤝" in s.candidates)
        // 你 reads ni alone: none of its
        assertFalse("🫵" in s.candidates)
        // nothing to pin, forget nor block
        assertEquals(emptySet<Offer>(), session.offers(2))
        assertEquals("👋", session.apply(Select(2)).commit)
    }

    @Test
    fun aPhraseIsWhereItsOrderSaysTheEmojiAside() {
        val book = PhraseBook(CustomPhrases.parse("nihao,3=你好呀"))
        val s = PinyinSession(data, PinyinSegmenter(), pageSize = 20, phraseBook = book, emoji = { emoji[it].orEmpty() }).type("nihao")
        assertEquals("你好呀", s.candidates[2])
        assertEquals(listOf("👋", "🙋"), s.candidates.subList(3, 5))
    }

    @Test
    fun noneWithoutTheSetting() {
        val s = PinyinSession(data, PinyinSegmenter(), pageSize = 20).type("nihao")
        assertFalse("👋" in s.candidates)
    }

    @Test
    fun theEmojiAreThoseOfTheCandidateShownFirst() {
        // a phrase pinned ahead of 你好: its emoji, not 你好's
        val book = PhraseBook(CustomPhrases.parse("nihao,1=嗨"))
        val more = emoji + ("嗨" to listOf("🙌"))
        val s = PinyinSession(data, PinyinSegmenter(), pageSize = 20, phraseBook = book, emoji = { more[it].orEmpty() }).type("nihao")
        assertEquals("嗨", s.candidates[0])
        // after the first two read, the phrase ahead of them
        assertEquals("🙌", s.candidates[3])
        assertFalse("👋" in s.candidates)
    }
}
