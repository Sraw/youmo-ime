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
import androidx.preference.PreferenceScreen
import androidx.navigation.fragment.findNavController
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
import org.fcitx.fcitx5.android.utils.addPreference

abstract class HostPreferenceFragment : PaddingPreferenceFragment() {
    abstract fun getPageTitle(): String
    /** The page's options by path; null if the host has none for it. */
    abstract suspend fun obtainConfig(fcitx: HostApi): Map<String, String>?
    abstract suspend fun saveConfig(fcitx: HostApi, newConfig: Map<String, String>)

    /** The page as shown and changed, on the main thread. */
    private val options = LinkedHashMap<String, String>()
    private var configLoaded = false

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob)

    private val viewModel: MainViewModel by activityViewModels()

    private val fcitx: HostConnection
        get() = viewModel.fcitx

    private fun save() {
        if (!configLoaded) return
        // a copy taken here, on the main thread: the page goes on changing its options while the
        // fcitx thread reads them, with nothing between the two
        val cfg = options.toMap()
        // launch "saveConfig" job under supervisorJob scope
        scope.launch {
            fcitx.runOnReady {
                saveConfig(this, cfg)
            }
        }
    }

    /** [block] on the fcitx thread, as [save] is: what the page does, saved before the page goes back too. */
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
     * **TLDR:**
     * Intentionally empty, since we need to create PreferenceScreen during onStart,
     * or it will crash when MainActivity relaunches.
     *
     * **Long version:**
     * When `MainActivity` relaunches, its `onCreate` get called, and somewhere in `super.onCreate`
     * decided to `restoreChildFragmentState` of `NavHostFragment`, thus recreate the child fragment.
     * If that fragment was derived from `HostPreferenceFragment`, it needs to call `obtainConfig`
     * which would need the route params, and in turn needs `NavGraph`.
     * But at this time it's still in `MainActivity`'s `super.onCreate`, the Activity did not have
     * chance to set up `NavGraph` on `navController`, so accessing `lazyRoute` would crash.
     *
     * That is to say, if we declare `app:navGraph` on `<FragmentContainerView />` in `activity_main.xml`,
     * the graph would have been initialized when `NavHostFragment` got inflated, and does not suffer
     * from this problem? But maintain navigation destinations in XML is too tedious ...
     */
    final override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) = Unit

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // make sure to create preference only once since `onViewCreated` is also called on Fragment resume
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

    /** The page over [options], [save] called after each change. */
    protected abstract fun createScreen(options: MutableMap<String, String>, save: () -> Unit = ::save): PreferenceScreen

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(getPageTitle())
    }
}