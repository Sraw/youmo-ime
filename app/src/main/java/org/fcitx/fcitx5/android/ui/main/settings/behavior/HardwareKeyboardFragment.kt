/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.behavior

import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodSettings
import org.fcitx.fcitx5.android.ui.main.settings.search.SearchHighlight

/**
 * The physical keyboard's page: its candidate window and shortcuts (the app's preferences), and
 * how many candidates the window shows, one of fcitx's options (InputMethodSettings.HARDWARE_KEYBOARD),
 * read from pinyin's config, which has those every input method shares, and added once fcitx answers.
 */
class HardwareKeyboardFragment : ManagedPreferenceFragment(AppPrefs.getInstance().candidates) {

    private val viewModel: MainViewModel by activityViewModels()

    override fun createUi(screen: PreferenceScreen) = build(screen)

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val fcitx = viewModel.fcitx
        lifecycleScope.launch {
            val cfg = fcitx.runOnReady { imOptions(InputMethodCatalog.PINYIN) }?.toMutableMap() ?: return@launch
            val items = InputMethodSettings.HARDWARE_KEYBOARD
            // on the connection's scope: a change made just before leaving the page is still saved
            InputMethodSettings.addTo(screen, items, cfg, save = {
                // taken now, on the main thread: the page goes on changing cfg
                val shown = cfg.toMap()
                fcitx.launchOnReady { save(it, shown) }
            }, open = {})
            // a search result for it opened the page before it was there
            view?.post { SearchHighlight.showIn(this@HardwareKeyboardFragment) }
        }
    }

    companion object {
        /** The page's managed preferences, as SettingsIndex reads them too. */
        fun build(screen: PreferenceScreen) = AppPrefs.getInstance().candidates.createUi(screen)

        /**
         * What the page shows alone, through pinyin: the shared options read with it may have been
         * changed on 通用输入 since, and writing them back would undo that.
         */
        suspend fun save(fcitx: HostApi, cfg: Map<String, String>) =
            fcitx.setImOptions(InputMethodCatalog.PINYIN, InputMethodSettings.only(InputMethodSettings.HARDWARE_KEYBOARD, cfg))
    }
}
