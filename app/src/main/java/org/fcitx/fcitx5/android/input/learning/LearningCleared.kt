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
 * A record that takes no [rewrites] says to restart the keyboard first: it takes none till the process
 * starts anew, so trying again fails again. Storage full is not that: space freed, the same clear works.
 */
@StringRes
internal fun clearedMessage(cleared: LearningResult?, rewrites: Boolean = true): Int = when (cleared) {
    is LearningResult.Cleared -> R.string.learning_cleared
    LearningResult.Failed -> if (rewrites) R.string.learning_clear_failed else R.string.learning_clear_failed_not_saving
    else -> R.string.learning_not_now
}

/** Not marked @StringRes: on a suspend fun lint sees the continuation's Object returned. */
internal suspend fun HostApi.clearAndTell(op: LearningOp): Int {
    val cleared = learning(op)
    return clearedMessage(cleared, rewrites = cleared != LearningResult.Failed || rewritesOrUnknown())
}

/** Reached only through the [BackupOp] overload: an undo that failed says its own. */
private suspend fun HostApi.failedMessage(): Int =
    if (rewritesOrUnknown()) R.string.learning_try_again else R.string.learning_try_again_not_saving

/** The rest (a copy, made in memory) never fail on a record that stopped. Every op named: a new one must say. */
internal suspend fun HostApi.failedMessage(op: BackupOp): Int = when (op) {
    is BackupOp.Restore, is BackupOp.RestoreKept, BackupOp.Revert -> failedMessage()
    BackupOp.Copy, BackupOp.Kept, is BackupOp.Delete, is BackupOp.Peek, is BackupOp.SetAuto -> R.string.learning_try_again
}

// unknown (no engine to answer) claims no restart
private suspend fun HostApi.rewritesOrUnknown() = (learning(LearningOp.Saving) as? LearningResult.Saving)?.rewrites != false
