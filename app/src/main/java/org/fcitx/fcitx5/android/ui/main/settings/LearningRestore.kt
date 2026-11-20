/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.engine.learn.backup.BackupHead
import org.fcitx.fcitx5.android.engine.learn.backup.LearnBackup
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.BackupResult
import org.fcitx.fcitx5.android.engine.user.WordListFiles
import org.fcitx.fcitx5.android.input.learning.failedMessage
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.utils.formatDateTime
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber

/**
 * Backup acts for the backups page and the first start (以前用过？从备份恢复, which picks the file itself as settings
 * are not reachable before setup). [fcitx] is asked lazily, so the first start runs fcitx only for a restore.
 */
internal class LearningRestore(
    private val fragment: Fragment,
    private val fcitx: () -> HostConnection,
    private val ran: () -> Unit = {},
) {
    private val ctx get() = fragment.requireContext()

    /** Null only when the record is not open (LearningResult.Unavailable: the phone not unlocked yet). */
    suspend fun backups(op: BackupOp): BackupResult? =
        fcitx().runOnReady { learning(op) } as? BackupResult

    fun source(head: BackupHead?, own: Boolean) = ctx.getString(
        when {
            head == null -> R.string.learning_backup_earlier_version
            own -> R.string.learning_backup_this_install
            else -> R.string.learning_backup_elsewhere
        },
    )

    fun confirmRestore(head: BackupHead?, own: Boolean, restore: () -> Unit) {
        // an older build's export has no head, so no date to say
        val message = head?.let { ctx.getString(R.string.learning_restore_confirm, formatDateTime(it.time), source(it, own)) }
            ?: ctx.getString(R.string.learning_restore_confirm_undated, source(null, own))
        confirm(R.string.learning_restore, message, restore)
    }

    fun confirm(title: Int, message: String, ok: () -> Unit) = confirmDialog(ctx, title, message, ok)

    /** The engine's reason is logged only (an exception's text); an unreadable file is told apart in [from]. */
    private suspend fun failed(ctx: Context, op: BackupOp, result: BackupResult?) {
        Timber.w("learning %s failed: %s", op.javaClass.simpleName, result?.error)
        ctx.toast(if (result == null) R.string.learning_not_now else fcitx().runOnReady { failedMessage(op) })
    }

    fun run(op: BackupOp, done: (BackupResult) -> Unit = {}) {
        val ctx = ctx
        fragment.lifecycleScope.withLoadingDialog(ctx) {
            val result = backups(op)
            if (result?.ok != true) failed(ctx, op, result) else done(result)
            ran()
        }
    }

    /** [saved] said once it is. */
    fun saveCopy(uri: Uri, saved: Int) {
        val ctx = ctx
        fragment.lifecycleScope.withLoadingDialog(ctx) {
            ctx.showingFileErrors("learning copy") {
                val copy = backups(BackupOp.Copy)
                val bytes = copy?.bytes
                if (bytes == null) {
                    failed(ctx, BackupOp.Copy, copy)
                    return@showingFileErrors
                }
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStreamOrThrow(uri).use { it.write(bytes) } }
                ctx.toast(saved)
            }
        }
    }

    /** A backup, a bare log, or the user dictionary's export: the engine tells which; each a restore (退回恢复前 undoes it). */
    fun from(uri: Uri) {
        val ctx = ctx
        fragment.lifecycleScope.withLoadingDialog(ctx) {
            ctx.showingFileErrors("learning restore") {
                // the picker takes any file: a wrong one (a video) is told from its head, never read whole, a backup read with a bound
                val bytes = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStreamOrThrow(uri).use { input ->
                        input.readPicked(HEAD, MAX_BACKUP) { LearnBackup.isBackup(it) || WordListFiles.isUserExport(it) }
                    }
                }
                if (bytes == null) {
                    ctx.toast(R.string.learning_backup_failed)
                    return@showingFileErrors
                }
                val peeked = backups(BackupOp.Peek(bytes))
                when {
                    peeked == null -> ctx.toast(R.string.learning_not_now)
                    // the engine's reason logged: what the user can do is the same
                    !peeked.ok -> {
                        Timber.w("learning restore: %s", peeked.error)
                        ctx.toast(R.string.learning_backup_failed)
                    }
                    else -> confirmRestore(peeked.head, peeked.own) { run(BackupOp.Restore(bytes)) { ctx.toast(R.string.learning_restored) } }
                }
            }
        }
    }

    companion object {
        // enough to tell a backup or an export (PinyinDictionaryFragment's peek)
        private const val HEAD = 64 shl 10

        // far more than years of typing make
        private const val MAX_BACKUP = 64 shl 20

        /** The one every learning and input method page asks. */
        fun confirmDialog(ctx: Context, title: Int, message: CharSequence, ok: () -> Unit) {
            AlertDialog.Builder(ctx)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> ok() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
