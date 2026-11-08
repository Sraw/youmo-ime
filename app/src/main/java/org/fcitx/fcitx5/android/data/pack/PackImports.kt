/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pack

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.pack.ImportResult
import org.fcitx.fcitx5.android.pack.PackKind
import org.fcitx.fcitx5.android.pack.PackStatus
import org.fcitx.fcitx5.android.pack.Refusal
import org.fcitx.fcitx5.android.utils.queryFileName

/**
 * The files the user picked on the 资源包 page, imported one after another on the app's scope: a
 * rotation, or the page left, does not stop them. The page shows [running] and, once all are done,
 * [results] (taken by [seen]).
 */
object PackImports {

    class Running(val file: String, val percent: Int)

    class Outcome(val file: String, val result: ImportResult)

    private val runningNow = MutableStateFlow<Running?>(null)
    val running: StateFlow<Running?> = runningNow.asStateFlow()

    private val done = MutableStateFlow<List<Outcome>?>(null)
    val results: StateFlow<List<Outcome>?> = done.asStateFlow()

    /** [uris] imported in turn; false (nothing started) if an import is under way. */
    fun start(context: Context, uris: List<Uri>): Boolean {
        if (runningNow.value != null || uris.isEmpty()) return false
        val resolver = context.applicationContext.contentResolver
        runningNow.value = Running("", 0)
        FcitxApplication.getInstance().coroutineScope.launch {
            val outcomes = ArrayList<Outcome>()
            try {
                for (uri in uris) {
                    val name = resolver.queryFileName(uri) ?: uri.lastPathSegment.orEmpty()
                    runningNow.value = Running(name, 0)
                    val result = PackStore.import(resolver, uri) { copied, total ->
                        val percent = if (total > 0) (copied * PERCENT / total).toInt() else 0
                        if (runningNow.value?.percent != percent) runningNow.value = Running(name, percent)
                    }
                    outcomes += Outcome(name, result)
                }
            } finally {
                done.value = outcomes
                runningNow.value = null
            }
        }
        return true
    }

    /** Tests: [outcomes] as an import's, for the page to show. */
    @VisibleForTesting
    fun finished(outcomes: List<Outcome>) {
        done.value = outcomes
    }

    /** The results shown: not shown again. */
    fun seen() {
        done.value = null
    }

    fun name(context: Context, kind: PackKind): String = context.getString(
        when (kind) {
            PackKind.TEXT -> R.string.pack_text
            PackKind.VOICE -> R.string.pack_voice
        },
    )

    /** What [status] is, for the pack's row. */
    fun describe(context: Context, status: PackStatus): String = when (status) {
        PackStatus.Missing -> context.getString(R.string.pack_status_missing)
        is PackStatus.Ready -> context.getString(R.string.pack_status_ready, status.manifest.version)
        is PackStatus.NeedsUpdate -> context.getString(R.string.pack_status_needs_update, status.needed.minVersion, status.manifest.version)
        is PackStatus.NeedsNewerApp -> context.getString(R.string.pack_status_needs_app, status.manifest.version)
    }

    /** What became of [outcome], a line of the results. */
    fun describe(context: Context, outcome: Outcome): String {
        val file = outcome.file
        return when (val result = outcome.result) {
            is ImportResult.Imported -> context.getString(R.string.pack_imported, name(context, result.manifest.kind), result.manifest.version)
            is ImportResult.AlreadyThere -> context.getString(R.string.pack_already_there, name(context, result.manifest.kind), result.manifest.version)
            is ImportResult.Refused -> when (val why = result.why) {
                is Refusal.NotAPack -> context.getString(R.string.pack_not_a_pack, file)
                is Refusal.BadSignature -> context.getString(R.string.pack_bad_signature, file)
                is Refusal.BadFile -> context.getString(R.string.pack_bad_file, file, why.path)
                is Refusal.Incompatible -> if (why.manifest.format > why.needed.format) {
                    context.getString(R.string.pack_incompatible_new, file, name(context, why.manifest.kind), why.manifest.version)
                } else {
                    context.getString(R.string.pack_incompatible_old, file, name(context, why.manifest.kind), why.manifest.version, why.needed.minVersion)
                }
                is Refusal.NoSpace -> context.getString(
                    R.string.pack_no_space, file, Formatter.formatShortFileSize(context, why.need), Formatter.formatShortFileSize(context, why.free),
                )
                is Refusal.Unreadable -> context.getString(R.string.pack_unreadable, file, why.detail)
            }
        }
    }

    private const val PERCENT = 100
}
