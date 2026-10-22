/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.english.EnglishUser
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.junit.Assert.assertEquals
import org.junit.Test

/** [LatinSync]: English's learned words listed in 全拼's 中英混输, made again as they change. */
class LatinSyncTest : EnginesFixture() {

    private val log = LearnLog(null)
    private val englishUser = EnglishUser { log }
    private val lexicon = UserLexicon(null, {}, { null }, { null }, EngineAssets(::load, {}, null), forgetInTables = {}, dropSessions = {})
    private val latin = LatinSync { false }

    private fun sync() = latin.sync(true, englishUser, { log.state }, lexicon)

    // review R11 F3023: English's words are its last 30 typing days': the window moving with no
    // English typed lists the words anew, one gone from it no more spelled
    @Test
    fun aWordOutOfEnglishsWindowIsNoMoreListedOnTheDayItLeaves() {
        englishUser.commit(null, "github")
        sync()
        assertEquals(setOf("github"), latin.words.words)
        // the record's typing day moved on, nothing else of it (no English typed, the same state)
        log.state.day += LearnState.WINDOW
        sync()
        assertEquals(emptySet<String>(), latin.words.words)
    }
}
