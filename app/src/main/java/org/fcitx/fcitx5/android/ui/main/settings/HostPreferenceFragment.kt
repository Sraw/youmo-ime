/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceScreen
import androidx.preference.isEmpty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.settings.search.ReadyLater
import org.fcitx.fcitx5.android.utils.addPreference

// its preferences come once fcitx answers: a search result waits for them
abstract class HostPreferenceFragment : PaddingPreferenceFragment(), ReadyLater {
    abstract fun getPageTitle(): String
    /** Null if the host has none for it. */
    abstract suspend fun obtainConfig(fcitx: HostApi): Map<String, String>?
    abstract suspend fun saveConfig(fcitx: HostApi, newConfig: Map<String, String>)

    /** Main thread. */
    private val options = LinkedHashMap<String, String>()
    private var configLoaded = false

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob)

    private val viewModel: MainViewModel by activityViewModels()

    private val fcitx: HostConnection
        get() = viewModel.fcitx

    private fun save() {
        if (!configLoaded) return
        // copied on the main thread: the page keeps changing its options while the fcitx thread reads them
        val cfg = options.toMap()
        scope.launch {
            fcitx.runOnReady {
                saveConfig(this, cfg)
            }
        }
    }

    /** As [save] is, so it is saved before the page goes back too. */
    protected fun launchOnFcitx(block: suspend HostApi.() -> Unit) {
        scope.launch { fcitx.runOnReady { block() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().onBackPressedDispatcher
            .addCallback(this, object : OnBackPressedCallback(true) {
                // prevent "back" from navigating away from this Fragment when it's still saving
                override fun handleOnBackPressed() {
                    lifecycleScope.withLoadingDialog(requireContext(), R.string.saving) {
                        // complete the parent job and wait all "saveConfig" jobs to finish
                        supervisorJob.complete()
                        supervisorJob.join()
                        scope.cancel()
                        findNavController().popBackStack()
                    }
                }
            })
    }

    /**
     * Intentionally empty: the screen is created in onStart. When MainActivity relaunches, `super.onCreate` restores the
     * child fragment before the NavGraph is set, and `obtainConfig` needs the route, so building it here would crash.
     */
    final override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) = Unit

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // `onViewCreated` is also called on Fragment resume: create preferences only once
        if (preferenceScreen?.isEmpty() == false) return
        val context = requireContext()
        lifecycleScope.withLoadingDialog(context) {
            val read = fcitx.runOnReady { obtainConfig(this) }
            configLoaded = read != null
            read?.let(options::putAll)
            preferenceScreen = if (configLoaded) {
                createScreen(options).apply {
                    if (isEmpty()) {
                        addPreference(R.string.no_config_options)
                    }
                }
            } else {
                preferenceManager.createPreferenceScreen(context).apply {
                    addPreference(R.string.config_addon_not_loaded)
                }
            }
        }
    }

    /** [save] called after each change. */
    protected abstract fun createScreen(options: MutableMap<String, String>, save: () -> Unit = ::save): PreferenceScreen

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(getPageTitle())
    }
}