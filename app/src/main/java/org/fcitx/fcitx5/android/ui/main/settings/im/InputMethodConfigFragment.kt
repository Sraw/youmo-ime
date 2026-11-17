/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.settings.HostPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim

class InputMethodConfigFragment : HostPreferenceFragment() {
    val args by lazyRoute<SettingsRoute.InputMethodConfig>()

    private val items by lazy { InputMethodSettings.of(args.uniqueName) }

    override fun getPageTitle(): String = InputMethodNames.of(requireContext(), args.uniqueName, args.name)

    override fun createScreen(options: MutableMap<String, String>, save: () -> Unit): PreferenceScreen =
        items?.let {
            InputMethodSettings.create(preferenceManager, it, options, save, act = { action, done ->
                launchOnFcitx {
                    val ok = action.run(this, args.uniqueName)
                    withContext(Dispatchers.Main) { done(ok) }
                }
            }) { route ->
                navigateWithAnim(route)
            }
        }
            // options the app has no page for: none shown (HostPreferenceFragment says so)
            ?: preferenceManager.createPreferenceScreen(requireContext())

    // 0.6.0's 手写 page, its route restored (a back stack saved before): 通用输入 in its place, its options neither read nor written
    private val retired get() = SettingsRoute.now(args) != args

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (retired) {
            view.post {
                // the page left before this runs (popped in the same frame, the state saved): no NavController to ask
                // (R26 F8404), as SearchHighlight.showIn's post
                if (!isAdded) return@post
                findNavController().popBackStack()
                findNavController().navigateWithAnim(SettingsRoute.now(args))
            }
        }
    }

    override suspend fun obtainConfig(fcitx: HostApi) = if (retired) emptyMap() else fcitx.imOptions(args.uniqueName)

    override suspend fun saveConfig(fcitx: HostApi, newConfig: Map<String, String>) {
        if (retired) return
        // what the page shows alone: the shared options it read may have been changed on the shared page since
        fcitx.setImOptions(args.uniqueName, items?.let { InputMethodSettings.only(it, newConfig) } ?: newConfig)
    }

    override fun onStop() {
        // 连按 and English's auto-caps are the keyboard's: before the first unlock it reads them from device-protected storage
        AppPrefs.getInstance().syncToDeviceEncryptedStorage()
        super.onStop()
    }
}
