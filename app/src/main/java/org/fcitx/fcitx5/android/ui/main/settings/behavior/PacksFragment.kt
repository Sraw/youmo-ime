/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

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
import org.fcitx.fcitx5.android.utils.setup
import org.fcitx.fcitx5.android.utils.toast

/**
 * 资源包 (dev/design/060-asset-packs.md): each pack's state, and the import of the files the user
 * picks (several at once), checked whole before any replaces what is there. The keyboard without
 * the text pack, and voice input without the voice pack, open this page.
 */
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
                key = "pack_${kind.id}"
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
                    // a .youmopack has no type of its own: any file, refused by its content if it is no pack
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
            }
        }
    }

    // on the main thread: PackStore.status reads the shelf loaded at the app's start (FcitxApplication warms it);
    // the page opened before that is done waits for it once, a settings page's moment, not the keyboard's
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

    private fun results(outcomes: List<PackImports.Outcome>) {
        val ctx = context ?: return
        if (outcomes.isEmpty()) return PackImports.seen()
        // the results collected again (back from the background) while their dialog is up: one dialog
        if (resultsDialog?.isShowing == true) return
        val lines = outcomes.map { PackImports.describe(ctx, it) }
        // the user's files are never deleted: they are told they may, once all of them are in
        val hint = when {
            outcomes.all { it.result !is ImportResult.Refused } -> ctx.getString(R.string.pack_import_done_hint)
            outcomes.none { it.result is ImportResult.Imported } -> ctx.getString(R.string.pack_unchanged_hint)
            else -> null
        }
        resultsDialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.pack_import_results)
            .setMessage((lines + listOfNotNull(hint)).joinToString("\n\n"))
            .setPositiveButton(android.R.string.ok, null)
            // seen once read: a rotation (the activity recreated) takes the dialog away unread, and the
            // results stay to be shown again (a refused file's reason seen nowhere else). Kept in memory
            // only: a process killed loses them
            .setOnDismissListener { PackImports.seen() }
            .show()
        show()
    }

    companion object {
        const val IMPORT_KEY = "pack_import"
    }
}
