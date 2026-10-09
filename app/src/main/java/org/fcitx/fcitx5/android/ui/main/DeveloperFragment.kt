/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import android.os.Debug
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.iso8601UTCDateTime
import org.fcitx.fcitx5.android.utils.setupForest
import org.fcitx.fcitx5.android.utils.startActivity
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import java.io.File

class DeveloperFragment : PaddingPreferenceFragment() {

    // the dump waiting on the file picker, which a recreation or process death of this page outlives
    private var hprofFile: File? = null
    private lateinit var launcher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cacheDir = requireContext().cacheDir
        hprofFile = savedInstanceState?.getString(HPROF_FILE)?.let { cacheDir.resolve(it) }
        // a dump no export is waiting on: the whole heap, typed text and clipboard with it
        cacheDir.listFiles()?.filter { it.name.endsWith(".hprof") && it != hprofFile }?.forEach { it.delete() }
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
            addPreference(R.string.real_time_logs) {
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
                    FcitxDaemon.getFirstConnectionOrNull()?.runIfReady {
                        setLogRule(verbose)
                    }
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
            addPreference(R.string.restart_fcitx_instance) {
                AlertDialog.Builder(context)
                    .setTitle(R.string.restart_fcitx_instance)
                    .setMessage(R.string.restart_fcitx_instance_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        lifecycleScope.launch {
                            FcitxDaemon.restartFcitx()
                            context.toast(R.string.done)
                        }
                    }
                    .show()
            }
            addPreference(R.string.delete_and_sync_data) {
                AlertDialog.Builder(context)
                    .setTitle(R.string.delete_and_sync_data)
                    .setMessage(R.string.delete_and_sync_data_message)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                DataManager.deleteAndSync()
                            }
                            context.toast(R.string.synced)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            addPreference(R.string.clear_clb_db) {
                AlertDialog.Builder(context)
                    .setTitle(R.string.clear_clb_db)
                    .setMessage(R.string.clear_clp_db_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                ClipboardManager.nukeTable()
                            }
                            context.toast(R.string.done)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            addPreference(R.string.capture_heap_dump) {
                val fileName = "${context.packageName}_${iso8601UTCDateTime()}.hprof"
                val file = context.cacheDir.resolve(fileName)
                hprofFile = file
                System.gc()
                // kept on the main thread: ART stops every thread for a dump, and no recreation can split dump and picker
                Debug.dumpHprofData(file.absolutePath)
                launcher.launch(fileName)
            }
        }
    }

    companion object {
        private const val HPROF_FILE = "hprof_file"
    }

}