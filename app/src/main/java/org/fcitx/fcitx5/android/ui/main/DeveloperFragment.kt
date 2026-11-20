/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Context
import android.os.Bundle
import android.os.Debug
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.HostDaemon
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.ui.main.settings.search.PageEntry
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchHighlight
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchablePage
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.iso8601UTCDateTime
import org.fcitx.fcitx5.android.utils.setupForest
import org.fcitx.fcitx5.android.utils.startActivity
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber

class DeveloperFragment : PaddingPreferenceFragment() {

    // the dump waiting on the file picker, which a recreation or process death of this page outlives
    private var hprofFile: File? = null
    private lateinit var launcher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cacheDir = requireContext().cacheDir
        hprofFile = savedInstanceState?.getString(HPROF_FILE)?.let { cacheDir.resolve(it) }
        // a whole heap with typed text and clipboard: deleted unless an export waits on it
        cacheDir.listFiles()?.let { staleDumps(it.asList(), hprofFile) }?.forEach { it.delete() }
        launcher = registerForActivityResult(CreateDocument("application/octet-stream")) { uri ->
            val file = hprofFile ?: return@registerForActivityResult
            hprofFile = null
            if (uri == null) {
                file.delete()
                return@registerForActivityResult
            }
            val ctx = requireContext()
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        ctx.contentResolver.openOutputStream(uri)!!.use { o ->
                            file.inputStream().use { i -> i.copyTo(o) }
                        }
                    }
                } catch (e: Exception) {
                    ctx.toast(e)
                } finally {
                    withContext(NonCancellable) {
                        withContext(Dispatchers.IO) {
                            file.delete()
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        hprofFile?.let { outState.putString(HPROF_FILE, it.name) }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(R.string.real_time_logs, key = LOGS_KEY) {
                startActivity<LogActivity>()
            }
            addPreference(MySwitchPreference(context).apply {
                key = AppPrefs.getInstance().internal.verboseLog.key
                setTitle(R.string.verbose_log)
                setDefaultValue(false)
                isIconSpaceReserved = false
                isSingleLineTitle = false
                setOnPreferenceChangeListener { _, newValue ->
                    val verbose = (newValue as? Boolean) == true
                    Timber.setupForest(verbose)
                    true
                }
            })
            addPreference(MySwitchPreference(context).apply {
                key = AppPrefs.getInstance().internal.editorInfoInspector.key
                setTitle(R.string.editor_info_inspector)
                setDefaultValue(false)
                isIconSpaceReserved = false
                isSingleLineTitle = false
            })
            addPreference(R.string.restart_fcitx_instance, key = RESTART_KEY) {
                AlertDialog.Builder(context)
                    .setTitle(R.string.restart_fcitx_instance)
                    .setMessage(R.string.restart_fcitx_instance_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        lifecycleScope.launch {
                            // waits for a host still starting: off the main thread
                            val restarted = withContext(Dispatchers.IO) { HostDaemon.restartFcitx() }
                            // a host still starting may not have restarted: not said done
                            context.toast(if (restarted) R.string.done else R.string.restart_fcitx_still_starting)
                        }
                    }
                    .show()
            }
            addPreference(R.string.clear_clb_db, key = CLEAR_CLIPBOARD_KEY) {
                AlertDialog.Builder(context)
                    .setTitle(R.string.clear_clb_db)
                    .setMessage(R.string.clear_clp_db_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        lifecycleScope.launch {
                            val cleared = withContext(Dispatchers.IO) {
                                ClipboardManager.nukeTable()
                            }
                            context.toastCleared(cleared)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            // 语音先问再记's counts, to see whether it asks too often
            addPreference(Preference(context).apply {
                val internal = AppPrefs.getInstance().internal
                title = getString(R.string.voice_ask_counts, internal.voiceAskShown.getValue(), internal.voiceAskTaken.getValue())
                isSelectable = false
                isIconSpaceReserved = false
                isSingleLineTitle = false
            })
            addPreference(R.string.capture_heap_dump, key = HEAP_DUMP_KEY) {
                val fileName = "${context.packageName}_${iso8601UTCDateTime()}.hprof"
                val file = context.cacheDir.resolve(fileName)
                hprofFile = file
                System.gc()
                // on the main thread: ART stops every thread anyway, and no recreation can split dump and picker
                Debug.dumpHprofData(file.absolutePath)
                launcher.launch(fileName)
            }
        }
    }

    // found a step lower than the rest; the counts are no setting
    companion object : SearchablePage {
        private const val HPROF_FILE = "hprof_file"
        private const val LOGS_KEY = "real_time_logs"
        private const val RESTART_KEY = "restart_fcitx"
        private const val CLEAR_CLIPBOARD_KEY = "clear_clipboard_db"
        private const val HEAP_DUMP_KEY = "capture_heap_dump"

        override fun searchEntries(context: Context): List<PageEntry> {
            val internal = AppPrefs.getInstance().internal
            return listOf(
                R.string.real_time_logs to LOGS_KEY,
                R.string.verbose_log to internal.verboseLog.key,
                R.string.editor_info_inspector to internal.editorInfoInspector.key,
                R.string.restart_fcitx_instance to RESTART_KEY,
                R.string.clear_clb_db to CLEAR_CLIPBOARD_KEY,
                R.string.capture_heap_dump to HEAP_DUMP_KEY,
            ).map { (title, key) -> PageEntry(context.getString(title), SearchHighlight.PREF + key) }
        }
    }

}

internal fun staleDumps(files: List<File>, keep: File?): List<File> =
    files.filter { it.name.endsWith(".hprof") && it != keep }

// one that left clips to come back says so, long enough to read
private fun Context.toastCleared(cleared: Boolean) =
    if (cleared) toast(R.string.done) else toast(R.string.clear_clp_db_not_all, Toast.LENGTH_LONG)
