/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.control

import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
import org.fcitx.fcitx5.android.engine.learn.FakeClock
import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.MineRow
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.Mine
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LexiconViewTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val clock = FakeClock()

    private fun LearnLog.commit(vararg rows: Row) = checkNotNull(record(rows.toList()))
    private fun word(text: String, idle: Int, order: Long) = UserWord(text, "", UserWord.Kind.ADDED, idle = idle, order = order)

    private val words = listOf(
        word("旧", 100, 1),
        word("新", 0, 3),
        word("中", LexiconView.UNUSED_DAYS - 1, 2),
        word("没", UserWord.NEVER, 0),
    )

    private fun texts(recent: Boolean, unused: Boolean) = LexiconView.arrange(words, recent, unused).joinToString("") { it.text }

    @Test
    fun asTheyComeOrLatestFirst() {
        assertEquals("旧新中没", texts(recent = false, unused = false))
        assertEquals("新中旧没", texts(recent = true, unused = false))
    }

    // a word whose last use is not known is not 「很久没用」: none goes in a bulk delete for it
    @Test
    fun onlyThoseKnownNotUsedForLong() {
        assertEquals("旧", texts(recent = false, unused = true))
        assertEquals("旧", texts(recent = true, unused = true))
        // from the day it is two months of typing
        assertTrue(LexiconView.unused(word("满", LexiconView.UNUSED_DAYS, 0)))
        assertFalse(LexiconView.unused(word("差", LexiconView.UNUSED_DAYS - 1, 0)))
    }

    // verify-E F3: when a word was last used, as the lexicon page lists it: idle in typing days, never a day not known
    @Test
    fun aWordsLastUseIsCountedInTypingDays() {
        val log = LearnLog(File(dir.root, LearnLog.FILE), clock).also { it.open() }
        log.commit(row("zd", "指导", "zhi dao"))
        repeat(3) {
            clock.days(5)
            log.commit(row("ni", "你", "ni"))
        }
        val words = listOf("指导", "没打过").map { UserWord(it, "", UserWord.Kind.ADDED) }
        val (used, never) = LexiconView.dated(log.state, words)
        // 15 days away, 3 of them typed on
        assertEquals(3, used.idle)
        assertEquals(LearnClock.localDay(clock, (FakeClock.START / LearnClock.MINUTE).toInt()), used.lastUsed)
        assertEquals(UserWord.NEVER, never.idle)
        assertEquals(UserWord.NEVER, never.lastUsed)
    }

    // 「最近学到」: by the typing day each became the user's, a merge having moved another install's
    // onto the phone's, then by seq; never by seqs of two installs' counts alone
    @Test
    fun theLatestLearnedFirstByTheirTypingDay() {
        val state = LearnState()
        // the phone's, day 30 at seq 5; a backup's, day 35 (moved) at its own seq 900; the phone's, day 40 at seq 8
        for ((text, day, seq) in listOf(Triple("早", 30, 5L), Triple("中", 35, 900L), Triple("晚", 40, 8L))) {
            state.apply(MineRow(text, Mine("", how = 0, since = day, sinceSeq = seq, lastUsedDay = day, lastUsedLocalDay = day, lastBy = seq)))
        }
        val words = listOf("中", "晚", "早").map { UserWord(it, "", UserWord.Kind.LEARNED) }
        assertEquals("晚中早", LexiconView.arrange(LexiconView.dated(state, words), recentFirst = true, unusedOnly = false).joinToString("") { it.text })
    }
}
