/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

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
        null, { throw it }, { null },
        EngineAssets({ ByteBuffer.wrap(pinyin) }, { throw it }, null),
        forgetInTables = {}, dropSessions = {},
    )

    // the tables once recoded the whole list at each English word learned
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
