/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.control

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.learn.FakeClock
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.backup.BackupHead
import org.fcitx.fcitx5.android.engine.learn.backup.BackupMerge
import org.fcitx.fcitx5.android.engine.learn.backup.LearnBackup
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** 清掉最近 and the words a table remembered. */
class ClearRecentTableWordsTest {
    private val dir = MemoryFiles()
    private val clock = FakeClock()

    // 清掉最近 is for a phone lent out: a word a table remembered meanwhile is no longer the user's; one before stays,
    // and one deleted meanwhile stays deleted
    @Test
    @Uat("PR-72", "TBL-11c")
    fun theWordsATableRememberedMeanwhileGoAndOlderOnesStay() {
        val log = LearnLog(dir.file(LearnLog.FILE), clock, { throw it }, compactAt = 1L shl 30).also { it.open() }
        log.rememberTableWord("engine-wubi", "iije", "汪小明")
        log.rememberTableWord("engine-wubi", "wqvb", "你好")
        clock.minutes(30)
        log.rememberTableWord("engine-erbi", "wcxm", "汪小明")
        log.dropTableWords(listOf(LearnRecord.TableWordRow("engine-wubi", "wqvb", "你好", log.state.tableWords.getValue(Triple("engine-wubi", "wqvb", "你好")))))
        val control = LearningControl({ log }, clock, legacy = { emptyList() }, onError = { throw it }).also { it.attach(log) }
        assertNotNull(control.clearRecent(LearningOp.Span.TEN_MINUTES))
        val expected = setOf(Triple("engine-wubi", "iije", "汪小明"))
        assertEquals(expected, log.state.tableWords.keys)
        assertEquals(expected, LearnLog.replay(dir.file(LearnLog.FILE).read()).tableWords.keys)
        // a backup made after the clear carries what the clear left, restored here or on another phone
        val backup = LearnBackup.read(LearnBackup.bytes(BackupHead(0L, 1, 1L, BackupHead.SAVED, "0.6.3"), log.state, emptyList(), emptyList())).state
        assertEquals(expected, BackupMerge.merge(LearnState(), backup, from = 1L, sameInstall = false, minute = 2000).tableWords.keys)
    }
}
