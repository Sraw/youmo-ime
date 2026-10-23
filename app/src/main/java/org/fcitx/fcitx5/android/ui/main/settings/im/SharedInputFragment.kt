/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.im

import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.ui.main.settings.FcitxPreferenceFragment
import org.fcitx.fcitx5.android.utils.navigateWithAnim

/**
 * What every input method shares ([InputMethodSettings.SHARED]), over pinyin's config: the addon
 * keeps one value of each, which pinyin's page reads and writes as any other's would, and pinyin's
 * has them all; pinyin need not be among the input methods turned on.
 */
class SharedInputFragment : FcitxPreferenceFragment() {

    override fun getPageTitle(): String = getString(R.string.shared_input)

    override fun createScreen(raw: RawConfig, save: () -> Unit): PreferenceScreen =
        InputMethodSettings.create(preferenceManager, InputMethodSettings.SHARED, raw["cfg"], save) { route -> navigateWithAnim(route) }

    override suspend fun obtainConfig(fcitx: FcitxAPI): RawConfig = fcitx.getImConfig(Engines.PINYIN)

    override suspend fun saveConfig(fcitx: FcitxAPI, newConfig: RawConfig) {
        // the shared options alone: pinyin's own on its page, which may be open behind this one
        fcitx.setImConfig(Engines.PINYIN, InputMethodSettings.only(InputMethodSettings.SHARED, newConfig))
    }

    override fun onStop() {
        // glide typing and the pairs are the keyboard's: before the first unlock it reads them from device-protected storage
        AppPrefs.getInstance().syncToDeviceEncryptedStorage()
        super.onStop()
    }
}
