/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import android.os.Build
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.settings.FcitxPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.navigateWithAnim

class InputMethodConfigFragment : FcitxPreferenceFragment() {
    val args by lazyRoute<SettingsRoute.InputMethodConfig>()

    private val items by lazy { InputMethodSettings.of(args.uniqueName) }

    override fun getPageTitle(): String = InputMethodNames.of(requireContext(), args.uniqueName, args.name)

    override fun createScreen(raw: RawConfig, save: () -> Unit): PreferenceScreen =
        items?.let { InputMethodSettings.create(preferenceManager, it, raw["cfg"], save) { route -> navigateWithAnim(route) } }
            ?: super.createScreen(raw, save)

    override suspend fun obtainConfig(fcitx: FcitxAPI): RawConfig {
        return fcitx.getImConfig(args.uniqueName)
    }

    override suspend fun saveConfig(fcitx: FcitxAPI, newConfig: RawConfig) {
        // what the page shows alone: the shared options it read may have been changed on the shared page since
        fcitx.setImConfig(args.uniqueName, items?.let { InputMethodSettings.only(it, newConfig) } ?: newConfig)
    }

    override fun onStop() {
        // 连按 and English's auto-caps are the keyboard's: before the first unlock it reads them from device-protected storage
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            AppPrefs.getInstance().syncToDeviceEncryptedStorage()
        }
        super.onStop()
    }
}
