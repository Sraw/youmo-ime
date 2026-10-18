/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment

/** The sound and vibration of a key press: the second section of AppPrefs.Keyboard, a page of its own. */
class SoundSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {

    override fun createUi(screen: PreferenceScreen) = build(screen)

    companion object {
        /** The page's managed preferences, as SettingsIndex reads them too. */
        fun build(screen: PreferenceScreen) = AppPrefs.getInstance().keyboard.createUi(screen, R.string.section_sound_and_vibration)
    }
}
