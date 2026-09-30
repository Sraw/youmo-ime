/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.navigateWithAnim

class MainFragment : PaddingPreferenceFragment() {

    override fun onViewCreated(
        view: View, savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)
        // AboutMenuProvider is tied to viewLifecycleOwner, so the about menu items
        // are automatically shown when this Fragment is visible and removed when it's not
        requireActivity().addMenuProvider(
            AboutMenuProvider(), viewLifecycleOwner, Lifecycle.State.STARTED
        )
        // plugins load asynchronously on a cold start, and may be reloaded from the plugin page.
        // Scoped to the view: setting preferenceScreen after onDestroyView (we're on the back
        // stack) would crash in PreferenceFragmentCompat.bindPreferences
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                DataManager.awaitSynced()
                if (rimeLoaded() != rimeShown) preferenceScreen = createPreferenceScreen()
            }
        }
    }

    private inner class AboutMenuProvider : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menu.item(R.string.faq) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Const.faqUrl)))
            }
            menu.item(R.string.developer) {
                navigateWithAnim(SettingsRoute.Developer)
            }
            menu.item(R.string.about) {
                navigateWithAnim(SettingsRoute.About)
            }
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
    }

    private fun PreferenceCategory.addDestinationPreference(
        @StringRes title: Int,
        @DrawableRes icon: Int,
        route: SettingsRoute
    ) {
        addPreference(title, icon = icon) {
            navigateWithAnim(route)
        }
    }

    private var rimeShown = false

    // the set is mutated by a running sync; only read it once that has finished
    private fun rimeLoaded() =
        DataManager.synced && DataManager.getLoadedPlugins().any { it.name == RIME_PLUGIN }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = createPreferenceScreen()
    }

    private fun createPreferenceScreen(): PreferenceScreen {
        rimeShown = rimeLoaded()
        // grouped by what the user wants to adjust, not by which layer (fcitx or Android) owns the setting
        return preferenceManager.createPreferenceScreen(requireContext()).apply {
            addCategory(R.string.home_section_input) {
                addDestinationPreference(
                    R.string.input_methods,
                    R.drawable.ic_baseline_language_24,
                    SettingsRoute.InputMethodList
                )
                // only once the plugin is loaded: before that the addon has no config to show
                if (rimeShown) {
                    addPreference(getString(R.string.rime), icon = R.drawable.ic_status_rime) {
                        navigateWithAnim(SettingsRoute.AddonConfig(getString(R.string.rime), RIME_ADDON))
                    }
                }
                addDestinationPreference(
                    R.string.global_options,
                    R.drawable.ic_baseline_tune_24,
                    SettingsRoute.GlobalConfig
                )
                addDestinationPreference(
                    R.string.addons,
                    R.drawable.ic_baseline_extension_24,
                    SettingsRoute.AddonList
                )
            }
            addCategory(R.string.home_section_keyboard) {
                addDestinationPreference(
                    R.string.virtual_keyboard,
                    R.drawable.ic_baseline_keyboard_24,
                    SettingsRoute.VirtualKeyboard
                )
                addDestinationPreference(
                    R.string.long_press_characters,
                    R.drawable.ic_baseline_text_format_24,
                    SettingsRoute.PopupOverrides
                )
                addDestinationPreference(
                    R.string.clipboard,
                    R.drawable.ic_clipboard,
                    SettingsRoute.Clipboard
                )
                addDestinationPreference(
                    R.string.emoji_and_symbols,
                    R.drawable.ic_baseline_emoji_symbols_24,
                    SettingsRoute.Symbol
                )
            }
            addCategory(R.string.home_section_appearance) {
                addDestinationPreference(
                    R.string.theme,
                    R.drawable.ic_baseline_palette_24,
                    SettingsRoute.Theme
                )
                addDestinationPreference(
                    R.string.candidates_window,
                    R.drawable.ic_baseline_list_alt_24,
                    SettingsRoute.CandidatesWindow
                )
            }
            addCategory(R.string.home_section_other) {
                addDestinationPreference(
                    R.string.plugins,
                    R.drawable.ic_baseline_android_24,
                    SettingsRoute.Plugin
                )
                addDestinationPreference(
                    R.string.advanced,
                    R.drawable.ic_baseline_more_horiz_24,
                    SettingsRoute.Advanced
                )
            }
        }
    }

    companion object {
        /** [org.fcitx.fcitx5.android.core.data.PluginDescriptor.name] of plugin/rime */
        private const val RIME_PLUGIN = "rime"
        /** fcitx5-rime's addon unique name */
        private const val RIME_ADDON = "rime"
    }
}
