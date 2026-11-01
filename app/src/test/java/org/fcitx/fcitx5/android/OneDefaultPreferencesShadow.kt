/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.content.Context
import android.content.SharedPreferences
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowContextImpl

/**
 * The default preferences (PreferenceManager.getDefaultSharedPreferences) one object for every test of
 * a sandbox, as on a device for the process: AppPrefs is made once per JVM (AppPrefs.init returns if
 * made) on the first test's, and a later test's pages saved to that test's own, which AppPrefs never
 * read (TB-45, ToolbarUatTest, TablesSettingsUatTest, ST-32 flaking by which tests ran before). Not
 * cleared between tests: the listeners old tests left on it would wake. Set for all in robolectric.properties.
 */
@Implements(className = "android.app.ContextImpl", isInAndroidSdk = false)
// Robolectric's own ContextImpl shadow extended, not replaced: else getExternalFilesDir and the rest are lost
class OneDefaultPreferencesShadow : ShadowContextImpl() {

    @RealObject
    private lateinit var context: Context

    // keyed by the store: the device-protected copy (createDeviceProtectedStorageContext) has the same
    // name and is a store of its own; locked, as the fcitx thread and the config writer both ask first
    @Implementation
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        if (name == null || !name.endsWith("_preferences")) return super.getSharedPreferences(name, mode)
        // not by the file: each test's data dir is a temporary one of its own
        val key = "$name ${context.isDeviceProtectedStorage}"
        return synchronized(shared) { shared.getOrPut(key) { super.getSharedPreferences(name, mode) } }
    }

    companion object {
        private val shared = HashMap<String, SharedPreferences>()
    }
}
