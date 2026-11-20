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
import androidx.preference.Preference
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.data.DataAge
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.openUrl
import org.fcitx.fcitx5.android.utils.setup
import org.fcitx.fcitx5.android.utils.styledColor
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class MainFragment : PaddingPreferenceFragment() {

    override fun onViewCreated(
        view: View, savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)
        // AboutMenuProvider is tied to viewLifecycleOwner, so the search, help and about menu items
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
            menu.item(R.string.help, R.drawable.ic_outline_help_outline_24, tint, showAsAction = true) {
                requireContext().openUrl(Const.helpUrl)
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

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // grouped by what the user adjusts, not by owner (fcitx or Android); fcitx's global options are not shown
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            // the offline build fetches nothing itself
            val dataMade = dataDay(BuildConfig.ENGINE_DATA, BuildConfig.ENGINE_WORDS) ?: BuildConfig.BUILD_TIME
            if (DataAge.isStale(dataMade, System.currentTimeMillis())) {
                addPreference(R.string.data_outdated, R.string.data_outdated_summary) {
                    requireContext().openUrl(Const.releasesUrl)
                }
            }
            HOME.forEach { row ->
                addPreference(row.title, row.summary, row.icon) { navigateWithAnim(row.route) }
            }
            addPreference(Preference(context).apply {
                // a row, not a setting: the settings search points at it by this key
                key = LANGUAGE_KEY
                isPersistent = false
                setup(
                    getString(R.string.app_language),
                    AppLanguage.name(context, AppLanguage.current()),
                    R.drawable.ic_baseline_translate_24
                ) {
                    // set here: a pick that leaves the configuration as it was recreates nothing
                    AppLanguage.choose(context) { summary = AppLanguage.name(context, it) }
                }
            })
        }
    }

    internal class Row(@StringRes val title: Int, @StringRes val summary: Int, @DrawableRes val icon: Int, val route: SettingsRoute)

    companion object {
        const val LANGUAGE_KEY = "app_language"

        /** The app's language last, whose summary is its value. */
        internal val HOME = listOf(
            Row(R.string.input_methods, R.string.home_input_methods_summary, R.drawable.ic_baseline_language_24, SettingsRoute.InputMethodList),
            Row(R.string.shared_input, R.string.home_shared_input_summary, R.drawable.ic_baseline_settings_24, SettingsRoute.SharedInput),
            Row(R.string.word_packs, R.string.home_words_summary, R.drawable.ic_baseline_list_alt_24, SettingsRoute.PinyinDict("")),
            Row(R.string.theme_and_size, R.string.home_look_summary, R.drawable.ic_baseline_palette_24, SettingsRoute.Theme),
            Row(R.string.virtual_keyboard, R.string.home_keys_summary, R.drawable.ic_baseline_space_bar_24, SettingsRoute.Keys),
            Row(R.string.section_sound_and_vibration, R.string.home_sound_summary, R.drawable.ic_baseline_volume_up_24, SettingsRoute.SoundAndVibration),
            Row(R.string.clipboard_and_emoji, R.string.home_clipboard_summary, R.drawable.ic_clipboard, SettingsRoute.Clipboard),
            Row(R.string.hardware_keyboard, R.string.home_hardware_summary, R.drawable.ic_baseline_keyboard_24, SettingsRoute.HardwareKeyboard),
            Row(R.string.user_data, R.string.home_backup_summary, R.drawable.ic_baseline_save_24, SettingsRoute.Backup),
            Row(R.string.packs, R.string.home_packs_summary, R.drawable.ic_baseline_library_books_24, SettingsRoute.Packs),
        )
    }
}

// engine-data-20261005-3, or a month, words-202610
private val RELEASE_DAY = Regex("""-(\d{8}|\d{6})(?:-|$)""")

/** The newest data release's day in epoch ms, not the build's: an old tree built today has old data. */
internal fun dataDay(vararg releases: String): Long? = releases.mapNotNull(::releaseDay).maxOrNull()

// a month's tag from its first day, the earliest it may have been made
private fun releaseDay(release: String): Long? {
    val day = RELEASE_DAY.find(release)?.groupValues?.get(1) ?: return null
    return SimpleDateFormat(if (day.length == 8) "yyyyMMdd" else "yyyyMM", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }.parse(day, ParsePosition(0))?.time
}
