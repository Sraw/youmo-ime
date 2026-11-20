/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.HostDaemon
import org.fcitx.fcitx5.android.data.UserDataManager
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.BackupResult
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.settings.openInputStreamOrThrow
import org.fcitx.fcitx5.android.ui.main.settings.openOutputStreamOrThrow
import org.fcitx.fcitx5.android.ui.main.settings.search.PageEntry
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchHighlight
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchablePage
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.formatDateTime
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.iso8601UTCDateTime
import org.fcitx.fcitx5.android.utils.queryFileName
import org.fcitx.fcitx5.android.utils.setup
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber

/** All user data in and out as one file: where it lies on the phone is not the user's business. */
class BackupFragment : PaddingPreferenceFragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private var exportTimestamp = System.currentTimeMillis()

    private lateinit var exportLauncher: ActivityResultLauncher<String>

    private lateinit var importLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                val cr = ctx.contentResolver
                lifecycleScope.withLoadingDialog(ctx) {
                    // no name check: providers may name it otherwise; a non-backup is refused by content (no metadata.json)
                    // outlives the page: once the engine's files are replaced the app must exit, else fcitx writes to the old ones
                    val failed = FcitxApplication.getInstance().coroutineScope.async(Dispatchers.Main.immediate) {
                        try {
                            val metadata = withContext(Dispatchers.IO) {
                                // stop fcitx before overwriting, off the main thread: a host stuck starting would read and write them as replaced, so
                                // the import stops there
                                if (!HostDaemon.stopFcitx()) errorRuntime(R.string.exception_user_data_host_starting)
                                ClipboardManager.closeDatabase()
                                // closed whatever fails, before the zip is read too
                                cr.openInputStreamOrThrow(uri).use { UserDataManager.import(it).getOrThrow() }
                            }
                            val exportTime = formatDateTime(metadata.exportTime)
                            ctx.toast(ctx.getString(R.string.user_data_imported, exportTime))
                            // the toast made before the process ends
                            delay(400L)
                            AppUtil.restart(ctx.applicationContext)
                            null
                        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                            // whatever was thrown: shown, after the clipboard is reopened and fcitx restarted
                            restartAfterFailedImport({ ClipboardManager.reopenDatabase() }) { HostDaemon.startFcitx() }
                            e
                        }
                    }
                    failed.await()?.let { ctx.importErrorDialog(it) }
                }
            }
        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                lifecycleScope.withLoadingDialog(ctx) {
                    try {
                        // taken once a file is picked, not before, so a page recreated under the picker still writes it; held only till written
                        val learning = viewModel.fcitx.runOnReady {
                            save()
                            (learning(BackupOp.Copy) as? BackupResult)?.bytes
                        }
                        withContext(Dispatchers.IO) {
                            val outputStream = ctx.contentResolver.openOutputStreamOrThrow(uri)
                            UserDataManager.export(outputStream, exportTimestamp, learning).getOrThrow()
                        }
                        // said: the picker closes on its own and nothing else shows it worked
                        val name = withContext(Dispatchers.IO) { ctx.contentResolver.queryFileName(uri) }
                        ctx.toast(ctx.getString(R.string.user_data_exported, name ?: uri.lastPathSegment.orEmpty()))
                    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                        // told, the page left as it was
                        Timber.w(e, "Failed to export the user's data")
                        ctx.toast(e)
                    }
                }
            }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        val screen = preferenceManager.createPreferenceScreen(ctx)
        preferenceScreen = screen
        screen.addPreference(Preference(ctx).apply {
            key = EXPORT_USER_DATA_KEY
            setup(ctx.getString(EXPORT_USER_DATA), ctx.getString(EXPORT_USER_DATA_SUMMARY)) {
                exportTimestamp = System.currentTimeMillis()
                exportLauncher.launch("youmo_${iso8601UTCDateTime(exportTimestamp)}.zip")
            }
        })
        screen.addPreference(Preference(ctx).apply {
            key = IMPORT_USER_DATA_KEY
            setup(ctx.getString(IMPORT_USER_DATA)) {
                AlertDialog.Builder(ctx)
                    .setIconAttribute(android.R.attr.alertDialogIcon)
                    .setTitle(R.string.import_user_data)
                    .setMessage(R.string.confirm_import_user_data)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        // every file: a renamed backup is no application/zip to the picker; a non-backup is refused by content
                        importLauncher.launch("*/*")
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        })
    }

    // hand-drawn here: the search finds them by these
    companion object : SearchablePage {
        @StringRes val EXPORT_USER_DATA = R.string.export_user_data
        @StringRes val EXPORT_USER_DATA_SUMMARY = R.string.export_user_data_summary
        @StringRes val IMPORT_USER_DATA = R.string.import_user_data
        const val EXPORT_USER_DATA_KEY = "export_user_data"
        const val IMPORT_USER_DATA_KEY = "import_user_data"

        override fun searchEntries(context: Context) = listOf(
            PageEntry(context.getString(EXPORT_USER_DATA), SearchHighlight.PREF + EXPORT_USER_DATA_KEY, summary = context.getString(EXPORT_USER_DATA_SUMMARY)),
            PageEntry(context.getString(IMPORT_USER_DATA), SearchHighlight.PREF + IMPORT_USER_DATA_KEY),
        )
    }
}

/**
 * Restarts fcitx first in any case, then reopens the clipboard if it can (that may throw); the import's error is the one
 * shown. An Error or a cancellation is rethrown.
 */
internal suspend fun restartAfterFailedImport(reopenClipboard: suspend () -> Unit, startFcitx: () -> Unit) {
    try {
        reopenClipboard()
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // a CancellationException is an Exception too
        currentCoroutineContext().ensureActive()
        Timber.w(e, "Failed to reopen the clipboard after an import")
    } finally {
        startFcitx()
    }
}
