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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.learn.backup.BackupInfo
import org.fcitx.fcitx5.android.engine.learn.backup.LearnBackup
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.settings.search.PageEntry
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchHighlight
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchablePage
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.formatDateTime
import org.fcitx.fcitx5.android.utils.toast

/** The engine merges a restore with what the phone has ([BackupOp]); the automatic backups' switch is on the lexicon page. */
class LearningBackupsFragment : PaddingPreferenceFragment() {

    private val viewModel: MainViewModel by activityViewModels()

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
        screen.addPreference(R.string.learning_restore, R.string.learning_restore_summary, key = RESTORE_KEY) {
            restoreLauncher.launch(arrayOf("*/*"))
        }
        screen.addPreference(R.string.learning_save_copy, R.string.learning_save_copy_summary, key = SAVE_COPY_KEY) {
            askSaveCopy(ctx, R.string.learning_save_copy, "youmo-learning", saveLauncher::launch)
        }
        revert = Preference(ctx).apply {
            key = REVERT_KEY
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
            // not readable yet (locked since boot): the list as it was
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
                        // asked, as a restore is: one copied in may be the only copy
                        val message = getString(R.string.learning_backup_delete_confirm, title, restore.source(info.head, info.own))
                        restore.confirm(R.string.learning_backup_delete, message) { restore.run(BackupOp.Delete(info.name)) }
                    }
                }
                .show()
            true
        }
    }

    private fun saveTo(uri: Uri) = restore.saveCopy(uri, R.string.learning_saved)

    companion object : SearchablePage {
        private const val RESTORE_KEY = "learning_restore"
        private const val SAVE_COPY_KEY = "learning_save_copy"
        // greyed while nothing has been restored, so search can still point at it
        private const val REVERT_KEY = "learning_revert"

        override fun searchEntries(context: Context) = listOf(
            Triple(R.string.learning_restore, RESTORE_KEY, R.string.learning_restore_summary),
            Triple(R.string.learning_save_copy, SAVE_COPY_KEY, R.string.learning_save_copy_summary),
            Triple(R.string.learning_revert, REVERT_KEY, R.string.learning_revert_summary),
        ).map { (title, key, summary) -> PageEntry(context.getString(title), SearchHighlight.PREF + key, summary = context.getString(summary)) }

        /** Asked first: anyone with the phone could take the file and what is typed often. */
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
