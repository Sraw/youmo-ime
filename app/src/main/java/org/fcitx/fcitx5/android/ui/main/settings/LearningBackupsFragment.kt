/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.learn.backup.BackupInfo
import org.fcitx.fcitx5.android.engine.learn.backup.LearnBackup
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.formatDateTime
import org.fcitx.fcitx5.android.utils.toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The learning's backups (U-100, U-103, U-106, D31): made every few typing days in the keyboard's
 * own folder, listed to restore or delete; one restored from a file the user picks (a backup, or
 * the user dictionary's export); a copy saved where they can see it; the last restore taken back.
 * A restore merges with what the phone has: the engine does it ([BackupOp]). The switch for the
 * automatic ones sits on the lexicon page's top, with 用我的输入习惯 (U-106, U-93).
 */
class LearningBackupsFragment : PaddingPreferenceFragment() {

    private val viewModel: MainViewModel by activityViewModels()

    // the page shown again after each act
    private val restore by lazy { LearningRestore(this, { viewModel.fcitx }) { refresh() } }

    private lateinit var restoreLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var saveLauncher: ActivityResultLauncher<String>

    private lateinit var revert: Preference
    private lateinit var kept: PreferenceCategory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(restore::from) }
        saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { it?.let(::saveTo) }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        val screen = preferenceManager.createPreferenceScreen(ctx)
        preferenceScreen = screen
        screen.addPreference(R.string.learning_restore, R.string.learning_restore_summary) {
            restoreLauncher.launch(arrayOf("*/*"))
        }
        screen.addPreference(R.string.learning_save_copy, R.string.learning_save_copy_summary) {
            askSaveCopy(ctx, R.string.learning_save_copy, "youmo-learning", saveLauncher::launch)
        }
        revert = Preference(ctx).apply {
            setTitle(R.string.learning_revert)
            setSummary(R.string.learning_revert_summary)
            isIconSpaceReserved = false
            isEnabled = false
            setOnPreferenceClickListener {
                restore.confirm(R.string.learning_revert, getString(R.string.learning_revert_confirm)) {
                    restore.run(BackupOp.Revert) { ctx.toast(R.string.learning_reverted) }
                }
                true
            }
        }
        screen.addPreference(revert)
        kept = PreferenceCategory(ctx).apply {
            setTitle(R.string.learning_backups_list)
            isIconSpaceReserved = false
        }
        screen.addPreference(kept)
        screen.addPreference(Preference(ctx).apply {
            setSummary(R.string.learning_uninstall_note)
            isIconSpaceReserved = false
            isSelectable = false
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            // not readable yet (the phone locked since boot): the list as it was
            val result = restore.backups(BackupOp.Kept) ?: return@launch
            revert.isEnabled = result.canRevert
            kept.removeAll()
            if (result.backups.isEmpty()) {
                kept.addPreference(Preference(requireContext()).apply {
                    setTitle(R.string.learning_backup_none)
                    isIconSpaceReserved = false
                    isSelectable = false
                })
            }
            result.backups.forEach { kept.addPreference(item(it)) }
        }
    }

    private fun item(info: BackupInfo) = Preference(requireContext()).apply {
        title = formatDateTime(info.head.time)
        summary = getString(R.string.learning_backup_item_summary, restore.source(info.head, info.own), info.head.day)
        isIconSpaceReserved = false
        setOnPreferenceClickListener {
            AlertDialog.Builder(context)
                .setTitle(title)
                .setItems(arrayOf(getString(R.string.learning_backup_restore), getString(R.string.learning_backup_delete))) { _, which ->
                    if (which == 0) {
                        restore.confirmRestore(info.head, info.own) {
                            restore.run(BackupOp.RestoreKept(info.name)) { context.toast(R.string.learning_restored) }
                        }
                    } else {
                        // asked, as a restore is: one copied in may be the only copy there is
                        val message = getString(R.string.learning_backup_delete_confirm, title, restore.source(info.head, info.own))
                        restore.confirm(R.string.learning_backup_delete, message) { restore.run(BackupOp.Delete(info.name)) }
                    }
                }
                .show()
            true
        }
    }

    private fun saveTo(uri: Uri) = restore.saveCopy(uri, R.string.learning_saved)

    companion object {
        /**
         * Asks before a copy of the learning is saved (U-106): anyone holding the phone can take the
         * file, and what is typed often with it. [save] given its name, [prefix] and the day, on OK.
         * The words' export too, the same file (review-uat-fixes S12). [confirm] shows the question.
         */
        fun askSaveCopy(
            ctx: Context,
            title: Int,
            prefix: String,
            save: (String) -> Unit,
            confirm: (title: Int, message: Int, ok: () -> Unit) -> Unit = { t, m, ok -> LearningRestore.confirmDialog(ctx, t, ctx.getString(m), ok) },
        ) = confirm(title, R.string.learning_save_copy_warning) {
            save("$prefix-${SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())}${LearnBackup.EXTENSION}")
        }
    }
}
