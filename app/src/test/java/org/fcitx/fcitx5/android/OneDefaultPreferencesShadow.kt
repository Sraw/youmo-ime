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
 * The default preferences one object for every test of a sandbox, as on a device: AppPrefs is made once per JVM on
 * the first test's, and a later test's pages saved to their own, which AppPrefs never read. Not cleared between
 * tests: the listeners old tests left on it would wake.
 */
@Implements(className = "android.app.ContextImpl", isInAndroidSdk = false)
// extended, not replaced: else getExternalFilesDir and the rest are lost
class OneDefaultPreferencesShadow : ShadowContextImpl() {

    @RealObject
    private lateinit var context: Context

    // keyed by the store: the device-protected copy has the same name and is a store of its own; locked, as the fcitx
    // thread and the config writer both ask first
    @Implementation
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        if (name == null || !name.endsWith("_preferences")) return super.getSharedPreferences(name, mode)
        // not by the file: each test's data dir is a temporary one of its own
        val key = "$name ${context.isDeviceProtectedStorage}"
        return synchronized(shared) {
            shared.getOrPut(key) {
                // 编码行 embedded for every test (its goldens and geometry); a test of the floating strip asks for it and puts it back
                super.getSharedPreferences(name, mode).also { it.edit().putString("code_row_style", "Embedded").commit() }
            }
        }
    }

    companion object {
        private val shared = HashMap<String, SharedPreferences>()
    }
}
