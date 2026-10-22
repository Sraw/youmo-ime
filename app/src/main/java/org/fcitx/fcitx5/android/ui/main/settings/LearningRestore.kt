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
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.engine.learn.backup.BackupHead
import org.fcitx.fcitx5.android.engine.learn.backup.LearnBackup
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.BackupResult
import org.fcitx.fcitx5.android.engine.user.WordListFiles
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.utils.formatDateTime
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber

/**
 * A backup restored, and the other acts on backups, from [fragment]: the backups page's and the
 * first start's (以前用过？从备份恢复, U-106), which picks the file itself, as the settings are not
 * reached before the setup is done (review-E-ui M1). [fcitx] is asked when first needed, so the
 * first start runs fcitx only for a restore; [ran] follows each act.
 */
internal class LearningRestore(
    private val fragment: Fragment,
    private val fcitx: () -> FcitxConnection,
    private val ran: () -> Unit = {},
) {
    private val ctx get() = fragment.requireContext()

    /**
     * The one call the engine takes (FcitxAPI.learning): a backup's op always answers a BackupResult,
     * null only when the record is not open (LearningResult.Unavailable: the phone not unlocked yet).
     */
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

    /**
     * Said on [ctx] that [result] failed, [op] logged with the engine's reason: that reason is an
     * exception's text, not for the user. A file that does not read is told apart in [from].
     */
    private fun failed(ctx: Context, op: BackupOp, result: BackupResult?) {
        Timber.w("learning %s failed: %s", op.javaClass.simpleName, result?.error)
        ctx.toast(if (result == null) R.string.learning_not_now else R.string.learning_try_again)
    }

    /** [op] done, said if it failed. */
    fun run(op: BackupOp, done: (BackupResult) -> Unit = {}) {
        val ctx = ctx
        fragment.lifecycleScope.withLoadingDialog(ctx) {
            val result = backups(op)
            if (result?.ok != true) failed(ctx, op, result) else done(result)
            ran()
        }
    }

    /** The phone's learning as a backup, saved to [uri] the user picked; [saved] said once it is. */
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

    /** A backup, a bare log, or the user dictionary's export the user picked: the engine tells which, and restores each as a restore (退回恢复前 takes it back). */
    fun from(uri: Uri) {
        val ctx = ctx
        fragment.lifecycleScope.withLoadingDialog(ctx) {
            ctx.showingFileErrors("learning restore") {
                // the picker takes any file: one picked by mistake (a video) is told from its head,
                // never read whole into memory, and a backup read with a bound (review R11)
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
        // as much of a file as tells a backup or an export (PinyinDictionaryFragment's peek)
        private const val HEAD = 64 shl 10

        // far more than years of typing make: a log's records are small
        private const val MAX_BACKUP = 64 shl 20

        /** An OK/Cancel question, [ok] done on OK: the one every learning and input method page asks. */
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
