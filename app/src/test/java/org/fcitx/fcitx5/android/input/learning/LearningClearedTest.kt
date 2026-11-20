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
 * A clear that failed on a record taking no rewrite says restart first: every retry would fail till the process
 * starts anew. Storage full is not that: space freed, the retry works, so it says try again.
 */
class LearningClearedTest {

    private val op = LearningOp.ClearRecent(LearningOp.Span.HOUR)

    // a write failed and the record did not open again: no rewrite till a restart
    private val stopped = LearningResult.Saving(false, rewrites = false)

    // storage full: a write refused, not saving, the record still open and taking rewrites
    private val full = LearningResult.Saving(false)

    private fun told(cleared: LearningResult, saving: LearningResult): Pair<Int, List<LearningOp>> {
        val api = FakeHostApi()
        val asked = mutableListOf<LearningOp>()
        api.learned = { asked += it; if (it == LearningOp.Saving) saving else cleared }
        return runBlocking { api.clearAndTell(op) } to asked
    }

    private fun failedTold(saving: LearningResult, backup: BackupOp): Int {
        val api = FakeHostApi()
        api.learned = { saving }
        return runBlocking { api.failedMessage(backup) }
    }

    @Test
    @Uat("WD-46")
    fun aFailedClearSaysToRestartOnlyWhenTheRecordTakesNoRewrite() {
        assertRows(
            "failed, record stopped" to {
                assertEquals(R.string.learning_clear_failed_not_saving to listOf(op, LearningOp.Saving), told(LearningResult.Failed, stopped))
            },
            "failed, storage full" to {
                assertEquals(R.string.learning_clear_failed to listOf(op, LearningOp.Saving), told(LearningResult.Failed, full))
            },
            // still saving: a disk that failed once, which may well work again
            "failed, saving" to {
                assertEquals(R.string.learning_clear_failed to listOf(op, LearningOp.Saving), told(LearningResult.Failed, LearningResult.Saving(true)))
            },
            // no engine to answer: nothing claimed of a restart
            "failed, saving unknown" to {
                assertEquals(R.string.learning_clear_failed to listOf(op, LearningOp.Saving), told(LearningResult.Failed, LearningResult.Unavailable))
            },
            "cleared" to {
                assertEquals(R.string.learning_cleared to listOf(op), told(LearningResult.Cleared(3), stopped))
            },
            "unavailable" to {
                assertEquals(R.string.learning_not_now to listOf(op), told(LearningResult.Unavailable, stopped))
            },
            // no answer at all is not now too
            "no answer" to { assertEquals(R.string.learning_not_now, clearedMessage(null)) },
        )
    }

    // a backup act tells the restart only if it rewrites the record; a copy is made in memory, so it says try again
    @Test
    @Uat("WD-46")
    fun aBackupActThatFailedSaysToRestartOnlyIfItRewritesTheRecord() {
        assertRows(
            "restore, record stopped" to { assertEquals(R.string.learning_try_again_not_saving, failedTold(stopped, BackupOp.Restore(ByteArray(0)))) },
            "restore of a kept backup, record stopped" to {
                assertEquals(R.string.learning_try_again_not_saving, failedTold(stopped, BackupOp.RestoreKept("a")))
            },
            "revert, record stopped" to { assertEquals(R.string.learning_try_again_not_saving, failedTold(stopped, BackupOp.Revert)) },
            "restore, storage full: try again" to { assertEquals(R.string.learning_try_again, failedTold(full, BackupOp.Restore(ByteArray(0)))) },
            "restore, saving: try again" to {
                assertEquals(R.string.learning_try_again, failedTold(LearningResult.Saving(true), BackupOp.Restore(ByteArray(0))))
            },
            "restore, saving unknown: try again" to {
                assertEquals(R.string.learning_try_again, failedTold(LearningResult.Unavailable, BackupOp.Restore(ByteArray(0))))
            },
            "copy, record stopped: try again" to { assertEquals(R.string.learning_try_again, failedTold(stopped, BackupOp.Copy)) },
            "delete, record stopped: try again" to { assertEquals(R.string.learning_try_again, failedTold(stopped, BackupOp.Delete("a"))) },
        )
    }
}
