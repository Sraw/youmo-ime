/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.os.Bundle
import androidx.annotation.CallSuper
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment

abstract class ManagedPreferenceFragment(private val preferenceProvider: ManagedPreferenceProvider) :
    PaddingPreferenceFragment() {

    private val evaluator = ManagedPreferenceVisibilityEvaluator(preferenceProvider) {
        lifecycleScope.launch {
            it.forEach { (key, enable) ->
                findPreference<Preference>(key)?.isEnabled = enable
            }
        }
    }

    /** The page's preferences put on [screen]: those of the provider, unless the page shows others too or only some. */
    open fun createUi(screen: PreferenceScreen) = preferenceProvider.createUi(screen)

    open fun onPreferenceUiCreated(screen: PreferenceScreen) {}

    @CallSuper
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        evaluator.evaluateVisibility()
        preferenceScreen =
            preferenceManager.createPreferenceScreen(preferenceManager.context).also { screen ->
                createUi(screen)
                onPreferenceUiCreated(screen)
            }
    }

    override fun onStop() {
        AppPrefs.getInstance().syncToDeviceEncryptedStorage()
        super.onStop()
    }

    override fun onDestroy() {
        evaluator.destroy()
        super.onDestroy()
    }
}