/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import org.fcitx.fcitx5.android.utils.Locales

/** An input method's window takes the display's configuration, the phone's language, even where Android 13 gives the app its own. */
class AppLanguage(private val service: Context) {

    /** Volatile, as is [localizedResources]: read off the main thread too, by a fcitx job's SubtypeManager.syncWith */
    @Volatile
    private var appLocales: LocaleListCompat? = null

    @Volatile
    private var localizedResources: Resources? = null

    private var localesReadWith: Pair<LocaleListCompat, LocaleListCompat>? = null

    fun resources(base: Resources): Resources {
        val locales = appLocales
        if (locales == null || locales.isEmpty) return base
        // the locales alone over the base: the system keeps these resources up with any other change, for those that hold them
        return localizedResources ?: service.createConfigurationContext(
            Configuration().also { ConfigurationCompat.setLocales(it, locales) }
        ).resources.also { localizedResources = it }
    }

    fun pickedChanged(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // reading the picked ones is a binder call there; they lead the app's configuration, so are read again only once it changed
            val languages = ConfigurationCompat.getLocales(service.applicationContext.resources.configuration) to
                ConfigurationCompat.getLocales(Resources.getSystem().configuration)
            if (languages == localesReadWith) return false
            localesReadWith = languages
        }
        val picked = Locales.picked(service)
        if (picked == appLocales) return false
        appLocales = picked
        localizedResources = null
        return true
    }
}
