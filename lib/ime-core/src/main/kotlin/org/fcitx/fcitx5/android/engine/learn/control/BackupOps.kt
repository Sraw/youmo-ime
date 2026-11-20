/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.control

import org.fcitx.fcitx5.android.engine.learn.backup.BackupHead
import org.fcitx.fcitx5.android.engine.learn.backup.BackupInfo

/** What the settings ask of the learning backups, through `learning(op)`. */
sealed class BackupOp : LearningOp() {
    /** The backups kept, and whether 退回恢复前 is possible. */
    data object Kept : BackupOp()

    class Delete(val name: String) : BackupOp()

    /** The head of a user-picked file, for confirmation; null for a bare log or a word-list export. */
    class Peek(val bytes: ByteArray) : BackupOp()

    /** A backup, a bare log, or the user dictionary's export. */
    class Restore(val bytes: ByteArray) : BackupOp()

    class RestoreKept(val name: String) : BackupOp()

    /** 退回恢复前: the learning as before the latest restore. */
    data object Revert : BackupOp()

    /** The phone's learning as backup bytes, for the user to save or the settings zip. */
    data object Copy : BackupOp()

    class SetAuto(val on: Boolean) : BackupOp()
}

/** [ok] false for an unreadable file or a failed disk ([error]). */
data class BackupResult(
    val ok: Boolean = true,
    val backups: List<BackupInfo> = emptyList(),
    val canRevert: Boolean = false,
    val head: BackupHead? = null,
    val own: Boolean = false,
    val bytes: ByteArray? = null,
    val error: String? = null,
) : LearningResult() {
    override fun equals(other: Any?) = other is BackupResult && ok == other.ok && backups == other.backups &&
        canRevert == other.canRevert && head == other.head && own == other.own && bytes.contentEquals(other.bytes) && error == other.error

    override fun hashCode() = (backups.hashCode() * 31 + head.hashCode()) * 31 + ok.hashCode()
}
