/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnCommit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The English page's words, as the learning record has them (U-31). */
class EnglishLexiconTest {

    private val log = LearnLog(null)
    private val english = EnglishLexicon { log }

    // typed lately or kept: a word whose correction was taken back is listed, never corrected, however long untyped
    @Test
    fun theWordsTypedAddedAndNeverCorrectedAreListed() {
        val user = english.user()
        user.commit(null, "the")
        user.commit(null, "the")
        user.commit(null, "teh", how = EnCommit.REVERTED)
        assertTrue(english.add("Youmo"))
        assertFalse(english.add("you mo"))
        assertEquals(
            listOf(
                UserWord("Youmo", "", UserWord.Kind.ADDED, 2f, neverCorrected = true),
                UserWord("the", "", UserWord.Kind.LEARNED, 2f),
                UserWord("teh", "", UserWord.Kind.LEARNED, 1f, neverCorrected = true),
            ),
            english.words(),
        )
        // 恢复 gives a word back to correction, still listed with its count; 删除 forgets it
        english.restoreCorrection(listOf("teh"))
        assertEquals(UserWord("teh", "", UserWord.Kind.LEARNED, 1f), english.words().last())
        english.forget(listOf("teh", "Youmo"))
        assertEquals(listOf("the"), english.words().map { it.text })
        assertFalse(english.user().neverCorrected("teh"))
    }

    @Test
    fun aRecordClosedIsReadAgain() {
        var record = LearnLog(null)
        val lexicon = EnglishLexicon { record }
        lexicon.user().commit(null, "the")
        assertEquals(listOf("the"), lexicon.words().map { it.text })
        // opened anew without the word: what the page lists is that record's, not the one closed
        record = LearnLog(null)
        lexicon.closed()
        assertEquals(emptyList<String>(), lexicon.words().map { it.text })
    }
}
