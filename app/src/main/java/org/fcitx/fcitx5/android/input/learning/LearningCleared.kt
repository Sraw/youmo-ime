/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.learning

import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult

/**
 * What a clear (清空学到的, ⋯'s 清掉最近) tells: works locked too (the record in memory then), what fails is the disk;
 * anything else is not now. A record that takes no [rewrites] says to restart the keyboard first: it takes none till
 * the process starts anew, so trying again fails again (F8022). Storage full is not that: the record is still open,
 * and space freed, the same clear works with no restart (F8037).
 */
@StringRes
internal fun clearedMessage(cleared: LearningResult?, rewrites: Boolean = true): Int = when (cleared) {
    is LearningResult.Cleared -> R.string.learning_cleared
    LearningResult.Failed -> if (rewrites) R.string.learning_clear_failed else R.string.learning_clear_failed_not_saving
    else -> R.string.learning_not_now
}

/**
 * Clears with [op] and returns what to tell of it, a string resource ([clearedMessage]); whether the record takes
 * rewrites is asked only when it failed. Not marked @StringRes: on a suspend fun lint sees the continuation's Object returned.
 */
internal suspend fun HostApi.clearAndTell(op: LearningOp): Int {
    val cleared = learning(op)
    return clearedMessage(cleared, rewrites = cleared != LearningResult.Failed || rewritesOrUnknown())
}

/**
 * What a backup's restore or revert that failed rewriting the record tells: to restart the keyboard first while the
 * record takes no rewrite, for the same reason as a clear (F8022), else to try again. Reached only through the
 * [BackupOp] overload below: an undo that failed says its own (learning_undo_failed), the act no longer undoable after it.
 */
private suspend fun HostApi.failedMessage(): Int =
    if (rewritesOrUnknown()) R.string.learning_try_again else R.string.learning_try_again_not_saving

/**
 * What a backup's [op] that failed tells: the restart only for the ops that rewrite the record ([failedMessage]); the
 * rest (a copy, made in memory) never fail on a record that stopped, so the restart is not their way out (F8041).
 * Every op named: a new one must say whether it rewrites the record.
 */
internal suspend fun HostApi.failedMessage(op: BackupOp): Int = when (op) {
    is BackupOp.Restore, is BackupOp.RestoreKept, BackupOp.Revert -> failedMessage()
    BackupOp.Copy, BackupOp.Kept, is BackupOp.Delete, is BackupOp.Peek, is BackupOp.SetAuto -> R.string.learning_try_again
}

// false only when the engine says the record takes no rewrite till a restart: unknown (no engine to answer) claims no restart
private suspend fun HostApi.rewritesOrUnknown() = (learning(LearningOp.Saving) as? LearningResult.Saving)?.rewrites != false
