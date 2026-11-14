/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.learning

import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.FakeHostApi
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A clear (清空学到的, ⋯'s 清掉最近) that failed on a record that takes no rewrite says to restart the keyboard first,
 * not to try again: the record takes none till the process starts anew, so every retry would fail (F8022). Storage
 * full is not that: the record is open, and space freed the retry works with no restart, so it says try again (F8037).
 */
class LearningClearedTest {

    private val op = LearningOp.ClearRecent(LearningOp.Span.HOUR)

    // a write failed and the record did not open again: no rewrite till a restart
    private val stopped = LearningResult.Saving(false, rewrites = false)

    // storage full: a write refused, not saving, the record still open and taking rewrites
    private val full = LearningResult.Saving(false)

    // what the clear tells with the clear answering [cleared] and LearningOp.Saving answering [saving], and the ops asked
    private fun told(cleared: LearningResult, saving: LearningResult): Pair<Int, List<LearningOp>> {
        val api = FakeHostApi()
        val asked = mutableListOf<LearningOp>()
        api.learned = { asked += it; if (it == LearningOp.Saving) saving else cleared }
        return runBlocking { api.clearAndTell(op) } to asked
    }

    // what a backup's [backup] that failed tells, LearningOp.Saving answering [saving]
    private fun failedTold(saving: LearningResult, backup: BackupOp): Int {
        val api = FakeHostApi()
        api.learned = { saving }
        return runBlocking { api.failedMessage(backup) }
    }

    @Test
    @Uat("WD-46")
    fun aFailedClearSaysToRestartOnlyWhenTheRecordTakesNoRewrite() {
        assertRows(
            // the record takes no rewrite: retrying fails till a restart, so the restart is what is said
            "failed, record stopped" to {
                assertEquals(R.string.learning_clear_failed_not_saving to listOf(op, LearningOp.Saving), told(LearningResult.Failed, stopped))
            },
            // storage full: not saving, but space freed the same clear works, no restart (F8037)
            "failed, storage full" to {
                assertEquals(R.string.learning_clear_failed to listOf(op, LearningOp.Saving), told(LearningResult.Failed, full))
            },
            // still saving: a disk that failed once, which may well work again
            "failed, saving" to {
                assertEquals(R.string.learning_clear_failed to listOf(op, LearningOp.Saving), told(LearningResult.Failed, LearningResult.Saving(true)))
            },
            // whether it saves not known (no engine to answer): nothing claimed of a restart
            "failed, saving unknown" to {
                assertEquals(R.string.learning_clear_failed to listOf(op, LearningOp.Saving), told(LearningResult.Failed, LearningResult.Unavailable))
            },
            // cleared: whether it saves is not asked
            "cleared" to {
                assertEquals(R.string.learning_cleared to listOf(op), told(LearningResult.Cleared(3), stopped))
            },
            // locked (not on disk): not now, not asked either
            "unavailable" to {
                assertEquals(R.string.learning_not_now to listOf(op), told(LearningResult.Unavailable, stopped))
            },
            // F3231: 清掉学到的 tells cleared, a disk that failed, or not now: no answer at all is not now too
            "no answer" to { assertEquals(R.string.learning_not_now, clearedMessage(null)) },
        )
    }

    // F8041: a backup act tells the restart only if it rewrites the record; a copy is made in memory, a stopped
    // record is not why it failed, so it says try again whatever the record does
    @Test
    @Uat("WD-46")
    fun aBackupActThatFailedSaysToRestartOnlyIfItRewritesTheRecord() {
        assertRows(
            "restore, record stopped" to { assertEquals(R.string.learning_try_again_not_saving, failedTold(stopped, BackupOp.Restore(ByteArray(0)))) },
            "restore of a kept backup, record stopped" to {
                assertEquals(R.string.learning_try_again_not_saving, failedTold(stopped, BackupOp.RestoreKept("a")))
            },
            "revert, record stopped" to { assertEquals(R.string.learning_try_again_not_saving, failedTold(stopped, BackupOp.Revert)) },
            // space freed, trying again works with no restart (F8037)
            "restore, storage full: try again" to { assertEquals(R.string.learning_try_again, failedTold(full, BackupOp.Restore(ByteArray(0)))) },
            // still saving: a disk that failed once, which may well work again
            "restore, saving: try again" to {
                assertEquals(R.string.learning_try_again, failedTold(LearningResult.Saving(true), BackupOp.Restore(ByteArray(0))))
            },
            // whether it saves not known (no engine to answer): nothing claimed of a restart
            "restore, saving unknown: try again" to {
                assertEquals(R.string.learning_try_again, failedTold(LearningResult.Unavailable, BackupOp.Restore(ByteArray(0))))
            },
            "copy, record stopped: try again" to { assertEquals(R.string.learning_try_again, failedTold(stopped, BackupOp.Copy)) },
            "delete, record stopped: try again" to { assertEquals(R.string.learning_try_again, failedTold(stopped, BackupOp.Delete("a"))) },
        )
    }
}
