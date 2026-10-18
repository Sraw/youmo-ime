/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import androidx.annotation.StringRes
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceCategory
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment

/** The clipboard, and the emoji's one setting under it: two small categories, one page. */
class ClipboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().clipboard) {

    override fun createUi(screen: PreferenceScreen) = build(screen)

    companion object {
        /** The page's managed preferences, as SettingsIndex reads them too. */
        fun build(screen: PreferenceScreen) {
            val prefs = AppPrefs.getInstance()
            section(screen, R.string.clipboard, prefs.clipboard)
            section(screen, R.string.section_emoji, prefs.symbols)
        }

        private fun section(screen: PreferenceScreen, @StringRes title: Int, category: ManagedPreferenceCategory) =
            category.createUi(ManagedPreferenceCategory.addSection(screen, title), ManagedPreferenceCategory.ALL)
    }
}
