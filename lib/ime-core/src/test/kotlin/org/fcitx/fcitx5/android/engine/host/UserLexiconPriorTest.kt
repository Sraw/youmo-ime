/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.Executor

// UserLexicon built directly over a pack with layers, which UserLexiconTest's Engines fixture lacks
class UserLexiconPriorTest {

    // 搭子 a word of the model's, in places
    private val pinyin = PinyinDataBuilder()
        .layers(listOf("base", "places"))
        .unigram("<unk>", -7f, 0f)
        .unigram("搭子", -3f, 0f)
        .entry("搭子", intArrayOf(Syllables.id("da"), Syllables.id("zi")), layer = 1)
        .build().toByteArray()

    private fun lexicon() = UserLexicon(
        null, { throw it }, { null }, { null },
        EngineAssets({ ByteBuffer.wrap(pinyin) }, { throw it }, null),
        forgetInTables = {}, dropSessions = {},
    )

    @Test
    fun aWordAddedByHandAtANewReadingTakesItsModelWordsLayerInTheEnginesPrior() {
        val lexicon = lexicon()
        val user = lexicon.user()
        val prior = lexicon.prior()
        assertTrue(prior.restore("places", 0.5f))
        assertTrue(lexicon.addWord("搭子", "da zhi"))
        val byHand = user.id(UserModel.Entry("搭子", intArrayOf(Syllables.id("da"), Syllables.id("zhi"))))
        assertTrue(user.modelWord(byHand) != byHand)
        // through UserLexicon's delegate: scored as the model's 搭子, so of places
        assertEquals(0.5f, prior.of(byHand), 0f)
    }

    // review-uat-fixes S8: English's words pinyin spells are no word of the user's dictionaries for
    // the tables, which recoded the whole list at each one learned
    @Test
    fun englishsWordsListedForPinyinLeaveTheTablesListAlone() {
        val lexicon = lexicon()
        val user = lexicon.user()
        val listings = user.listings
        lexicon.listLatin(setOf("GitHub"))
        assertEquals(listings, user.listings)
        assertFalse("GitHub" in user.listedTexts())
        // listed all the same: pinyin's model has the word, and not once unlisted
        assertTrue(user.knowsText("GitHub"))
        lexicon.listLatin(emptySet())
        assertEquals(listings, user.listings)
        assertFalse(user.knowsText("GitHub"))
    }
}
