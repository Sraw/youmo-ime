/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.core.InputMethodNames

import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.LibimeMigration
import org.fcitx.fcitx5.android.ui.main.settings.FcitxPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.lazyRoute

class InputMethodConfigFragment : FcitxPreferenceFragment() {
    val args by lazyRoute<SettingsRoute.InputMethodConfig>()

    override fun getPageTitle(): String = InputMethodNames.of(requireContext(), args.uniqueName, args.name)

    override fun createScreen(raw: RawConfig, save: () -> Unit): PreferenceScreen =
        InputMethodSettings.of(args.uniqueName)?.let { InputMethodSettings.create(preferenceManager, it, raw["cfg"], save) }
            ?: super.createScreen(raw, save)

    override suspend fun obtainConfig(fcitx: FcitxAPI): RawConfig {
        return fcitx.getImConfig(args.uniqueName)
    }

    override suspend fun saveConfig(fcitx: FcitxAPI, newConfig: RawConfig) {
        fcitx.setImConfig(args.uniqueName, newConfig)
        // the engine reads an imported table's options only as it loads the table: loaded again, they apply now
        if (fcitx.availableIme().any { it.uniqueName == args.uniqueName && isImportedTable(it) }) fcitx.reloadEngine()
    }
}

/**
 * Whether [entry] is a table the user imported: one of the engine's that it does not list itself,
 * whose options are its own file (`table/<im>.conf`), not the engine's config.
 */
internal fun isImportedTable(entry: InputMethodEntry): Boolean =
    entry.addon == LibimeMigration.ENGINE_ADDON && entry.uniqueName !in Engines.TABLES &&
        entry.uniqueName !in listOf(Engines.PINYIN, Engines.SHUANGPIN, Engines.T9)