/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.control

import org.fcitx.fcitx5.android.engine.learn.backup.BackupHead
import org.fcitx.fcitx5.android.engine.learn.backup.BackupInfo

/** What the settings ask of the learning backups (U-100, U-106; impl/streams/D-design.md), through the one `learning(op)`. */
sealed class BackupOp : LearningOp() {
    /** The backups kept, and whether 退回恢复前 can be done (the U-86/U-88 dialogs ask too). */
    data object Kept : BackupOp()

    class Delete(val name: String) : BackupOp()

    /** The head of [bytes], a file the user picked, for the confirmation; null head for a bare log or a word list's export. */
    class Peek(val bytes: ByteArray) : BackupOp()

    /** Merges [bytes] into the phone's learning: a backup, a bare log, or the user dictionary's export. */
    class Restore(val bytes: ByteArray) : BackupOp()

    /** Merges the kept backup [name] in. */
    class RestoreKept(val name: String) : BackupOp()

    /** 退回恢复前: the learning as it was before the latest restore, whole. */
    data object Revert : BackupOp()

    /** The phone's learning as a backup's bytes, to be saved where the user can see it, or into the settings' zip. */
    data object Copy : BackupOp()

    class SetAuto(val on: Boolean) : BackupOp()
}

/** What a [BackupOp] gave: [ok] false for a file that does not read, or a disk that failed (said in [error]). */
data class BackupResult(
    val ok: Boolean = true,
    val backups: List<BackupInfo> = emptyList(),
    val canRevert: Boolean = false,
    val head: BackupHead? = null,
    /** Whether [head] is of a backup this install made. */
    val own: Boolean = false,
    val bytes: ByteArray? = null,
    val error: String? = null,
) : LearningResult() {
    override fun equals(other: Any?) = other is BackupResult && ok == other.ok && backups == other.backups &&
        canRevert == other.canRevert && head == other.head && own == other.own && bytes.contentEquals(other.bytes) && error == other.error

    override fun hashCode() = (backups.hashCode() * 31 + head.hashCode()) * 31 + ok.hashCode()
}
