/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.CLIPBOARD_UNLIMITED
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceCategory
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.EditTextIntPreference

/** The clipboard, and the emoji's one setting under it: two small categories, one page. */
class ClipboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().clipboard) {

    override fun createUi(screen: PreferenceScreen) = build(screen)

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val limit = screen.findPreference<EditTextIntPreference>(AppPrefs.getInstance().clipboard.clipboardHistoryLimit.key) ?: return
        // a limit that deletes entries, which no undo brings back (thousands with 「无上限」 left), asks first;
        // the old value kept till then, and on cancel
        limit.setOnPreferenceChangeListener { _, value ->
            val to = value as? Int ?: return@setOnPreferenceChangeListener true
            // raised: nothing to count, set at once
            val from = AppPrefs.getInstance().clipboard.clipboardHistoryLimit.getValue()
            if (to == CLIPBOARD_UNLIMITED || (from != CLIPBOARD_UNLIMITED && to >= from)) return@setOnPreferenceChangeListener true
            viewLifecycleOwner.lifecycleScope.launch {
                val deleted = ClipboardManager.outdatedBy(to)
                if (deleted == 0) {
                    limit.text = to.toString()
                    return@launch
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.clipboard_limit)
                    .setMessage(resources.getQuantityString(R.plurals.clipboard_limit_deletes, deleted, deleted))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ -> limit.text = to.toString() }
                    .show()
            }
            false
        }
    }

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
