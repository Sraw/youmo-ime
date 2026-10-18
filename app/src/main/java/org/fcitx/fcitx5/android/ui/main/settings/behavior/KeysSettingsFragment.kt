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

/** The keys (按键): the first section of AppPrefs.Keyboard, the sound and vibration [SoundSettingsFragment]'s. */
class KeysSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {

    override fun createUi(screen: PreferenceScreen) = build(screen)

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.addPreference(Preference(requireContext()).apply {
            // managed preferences don't reserve an icon slot; match them or the title sits indented
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setTitle(R.string.long_press_characters)
            setSummary(R.string.long_press_characters_summary)
            setOnPreferenceClickListener {
                navigateWithAnim(SettingsRoute.PopupOverrides)
                true
            }
        })
    }

    companion object {
        /** The page's managed preferences, as SettingsIndex reads them too. */
        fun build(screen: PreferenceScreen) = AppPrefs.getInstance().keyboard.createUi(screen, R.string.section_keys)
    }
}
