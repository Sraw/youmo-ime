/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.pack.PackImports
import org.fcitx.fcitx5.android.data.pack.PackStore
import org.fcitx.fcitx5.android.pack.ImportResult
import org.fcitx.fcitx5.android.pack.PackKind
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.search.PageEntry
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchHighlight
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchablePage
import org.fcitx.fcitx5.android.utils.setup
import org.fcitx.fcitx5.android.utils.toast

/** Picked files (several at once) are checked whole before replacing anything. */
class PacksFragment : PaddingPreferenceFragment() {

    private lateinit var picker: ActivityResultLauncher<Array<String>>

    private val rows = HashMap<PackKind, Preference>()
    private lateinit var importRow: Preference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            if (!PackImports.start(requireContext(), uris)) requireContext().toast(R.string.pack_import_busy)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        val screen = preferenceManager.createPreferenceScreen(ctx)
        preferenceScreen = screen
        for (kind in PackKind.entries) {
            val row = Preference(ctx).apply {
                key = rowKey(kind)
                isSelectable = false
                setup(PackImports.name(ctx, kind))
            }
            rows[kind] = row
            screen.addPreference(row)
        }
        importRow = Preference(ctx).apply {
            key = IMPORT_KEY
            setup(ctx.getString(R.string.pack_import), ctx.getString(R.string.pack_import_summary)) {
                if (PackImports.running.value != null) {
                    ctx.toast(R.string.pack_import_busy)
                } else {
                    // a .youmopack has no MIME type: any file, refused by content if no pack
                    picker.launch(arrayOf("*/*"))
                }
            }
        }
        screen.addPreference(importRow)
        screen.addPreference(Preference(ctx).apply {
            isSelectable = false
            isIconSpaceReserved = false
            summary = ctx.getString(R.string.pack_about)
        })
        show()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { PackStore.state.collect { show() } }
                launch { PackImports.running.collect { show() } }
                launch { PackImports.results.collect { it?.let(::results) } }
                launch { PackImports.replaceQuestion.collect { it?.let(::askOlder) } }
            }
        }
    }

    // on the main thread: PackStore.status reads the shelf FcitxApplication warms; a page opened earlier waits once
    private fun show() {
        val ctx = context ?: return
        for ((kind, row) in rows) {
            row.summary = "${PackImports.describe(ctx, PackStore.status(kind))}\n" + ctx.getString(
                when (kind) {
                    PackKind.TEXT -> R.string.pack_text_use
                    PackKind.VOICE -> R.string.pack_voice_use
                },
            )
        }
        val running = PackImports.running.value
        importRow.summary = if (running != null && running.file.isNotEmpty()) {
            ctx.getString(R.string.pack_importing, running.file, running.percent)
        } else {
            ctx.getString(R.string.pack_import_summary)
        }
    }

    private var resultsDialog: AlertDialog? = null

    private var olderDialog: AlertDialog? = null

    // unanswered and gone with the activity, it is asked again by the recreated page; recollected while shown, still one dialog
    private fun askOlder(question: PackImports.ReplaceNewerQuestion) {
        val ctx = context ?: return
        if (olderDialog?.isShowing == true) return
        olderDialog = AlertDialog.Builder(ctx)
            .setTitle(PackImports.name(ctx, question.kind))
            // of several picked, which file is asked
            .setMessage(question.file + "\n\n" + ctx.getString(R.string.pack_replace_newer, question.installed, question.version))
            .setPositiveButton(R.string.pack_replace) { _, _ -> PackImports.answer(question, true) }
            .setNegativeButton(R.string.pack_keep_newer) { _, _ -> PackImports.answer(question, false) }
            .setOnCancelListener { PackImports.answer(question, false) }
            .show()
    }

    private fun results(outcomes: List<PackImports.Outcome>) {
        val ctx = context ?: return
        if (outcomes.isEmpty()) return PackImports.seen()
        // recollected while shown: one dialog
        if (resultsDialog?.isShowing == true) return
        val lines = outcomes.map { PackImports.describe(ctx, it) }
        // the user's files are never deleted: they are told they may, once all are in
        val hint = when {
            // a newer pack kept (保留) is not "done"
            outcomes.all { it.result is ImportResult.Imported || it.result is ImportResult.AlreadyThere } ->
                ctx.getString(R.string.pack_import_done_hint)
            outcomes.none { it.result is ImportResult.Imported } -> ctx.getString(R.string.pack_unchanged_hint)
            else -> null
        }
        resultsDialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.pack_import_results)
            .setMessage((lines + listOfNotNull(hint)).joinToString("\n\n"))
            .setPositiveButton(android.R.string.ok, null)
            // seen once read: a rotation dismisses it unread and it shows again; memory only, lost with the process
            .setOnDismissListener { PackImports.seen() }
            .show()
        show()
    }

    // the dialogs go unanswered with the view and the recreated page shows them again
    override fun onDestroyView() {
        for (dialog in listOfNotNull(olderDialog, resultsDialog)) {
            dialog.setOnCancelListener(null)
            dialog.setOnDismissListener(null)
            dialog.dismiss()
        }
        olderDialog = null
        resultsDialog = null
        super.onDestroyView()
    }

    companion object : SearchablePage {
        const val IMPORT_KEY = "pack_import"

        private fun rowKey(kind: PackKind) = "pack_${kind.id}"

        override fun searchEntries(context: Context) = PackKind.entries.map {
            PageEntry(PackImports.name(context, it), SearchHighlight.PREF + rowKey(it))
        } + PageEntry(context.getString(R.string.pack_import), SearchHighlight.PREF + IMPORT_KEY, summary = context.getString(R.string.pack_import_summary))
    }
}
