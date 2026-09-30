/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim

class KeyboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {
    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.addPreference(Preference(requireContext()).apply {
            setTitle(R.string.long_press_characters)
            setSummary(R.string.long_press_characters_summary)
            setOnPreferenceClickListener {
                navigateWithAnim(SettingsRoute.PopupOverrides)
                true
            }
        })
    }
}
