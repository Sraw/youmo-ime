/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.preference.PreferenceCategory
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.data.DataAge
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.openUrl
import org.fcitx.fcitx5.android.utils.styledColor

class MainFragment : PaddingPreferenceFragment() {

    override fun onViewCreated(
        view: View, savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)
        // AboutMenuProvider is tied to viewLifecycleOwner, so the search and about menu items
        // are automatically shown when this Fragment is visible and removed when it's not
        requireActivity().addMenuProvider(
            AboutMenuProvider(), viewLifecycleOwner, Lifecycle.State.STARTED
        )
    }

    private inner class AboutMenuProvider : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            val tint = requireContext().styledColor(android.R.attr.colorControlNormal)
            menu.item(R.string.search_settings, R.drawable.ic_baseline_search_24, tint, showAsAction = true) {
                navigateWithAnim(SettingsRoute.Search)
            }
            menu.item(R.string.faq) {
                requireContext().openUrl(Const.faqUrl)
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

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // grouped by what the user wants to adjust, not by which layer (fcitx or Android) owns the
        // setting; what a phone's user seldom needs (a physical keyboard's candidate window, fcitx's
        // global options) is under Advanced
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            // the offline build fetches nothing itself: new words come with a new version or a pack
            if (DataAge.isStale(BuildConfig.BUILD_TIME, System.currentTimeMillis())) {
                addPreference(R.string.data_outdated, R.string.data_outdated_summary) {
                    requireContext().openUrl(Const.releasesUrl)
                }
            }
            addCategory(R.string.home_section_input) {
                addDestinationPreference(
                    R.string.input_methods,
                    R.drawable.ic_baseline_language_24,
                    SettingsRoute.InputMethodList
                )
            }
            addCategory(R.string.home_section_words) {
                // the user dictionary is the first of the three layers on the dictionaries' page:
                // one way in, not a second one here
                addDestinationPreference(
                    R.string.custom_phrases,
                    R.drawable.ic_baseline_text_format_24,
                    SettingsRoute.PinyinCustomPhrase
                )
                addDestinationPreference(
                    R.string.word_packs,
                    R.drawable.ic_baseline_list_alt_24,
                    SettingsRoute.PinyinDict("")
                )
            }
            addCategory(R.string.home_section_keyboard_look) {
                addDestinationPreference(
                    R.string.theme_and_size,
                    R.drawable.ic_baseline_palette_24,
                    SettingsRoute.Theme
                )
                addDestinationPreference(
                    R.string.virtual_keyboard,
                    R.drawable.ic_baseline_keyboard_24,
                    SettingsRoute.VirtualKeyboard
                )
            }
            addCategory(R.string.home_section_clipboard_emoji) {
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
            addCategory(R.string.home_section_other) {
                addPreference(
                    R.string.app_language,
                    getString(AppLanguage.label(AppLanguage.current())),
                    R.drawable.ic_baseline_language_24
                ) { AppLanguage.choose(requireContext()) }
                addDestinationPreference(
                    R.string.advanced,
                    R.drawable.ic_baseline_more_horiz_24,
                    SettingsRoute.Advanced
                )
            }
        }
    }
}
