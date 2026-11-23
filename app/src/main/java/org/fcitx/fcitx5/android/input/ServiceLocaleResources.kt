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
import java.util.concurrent.atomic.AtomicReference

/** An input method's window takes the display's configuration, the phone's language, even where Android 13 gives the app its own. */
class ServiceLocaleResources(private val service: Context) {

    private class Localized(val locales: LocaleListCompat?, val resources: Resources?)

    // one value for both, swapped whole: read off the main thread too, by a fcitx job's SubtypeManager.syncWith, a pick
    // landing while that one builds must not have the old language's resources kept after it
    private val localized = AtomicReference(Localized(null, null))

    private var localesReadWith: Pair<LocaleListCompat, LocaleListCompat>? = null

    fun resources(base: Resources): Resources {
        val now = localized.get()
        val locales = now.locales
        if (locales == null || locales.isEmpty) return base
        now.resources?.let { return it }
        // the locales alone over the base: the system keeps these resources up with any other change, for those that hold them
        val made = service.createConfigurationContext(Configuration().also { ConfigurationCompat.setLocales(it, locales) }).resources
        localized.compareAndSet(now, Localized(locales, made))
        return made
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
        if (picked == localized.get().locales) return false
        localized.set(Localized(picked, null))
        return true
    }
}
