/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import java.util.Locale

object Locales {

    /**
     * The languages the user picked for the app (in its settings or, from Android 13 on, the
     * phone's), empty for the phone's own. Below 13 appcompat keeps them, but knows them only
     * once an activity of the app has started: the keyboard, started first, reads the app's copy.
     */
    fun picked(context: Context): LocaleListCompat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleListCompat.wrap(context.getSystemService(LocaleManager::class.java).applicationLocales)
        } else {
            AppCompatDelegate.getApplicationLocales().takeUnless { it.isEmpty }
                ?: AppPrefs.getInstance().internal.appLanguage.getValue().let {
                    if (it.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(it)
                }
        }

    lateinit var fcitxLocale: String
        private set

    lateinit var language: String
        private set

    lateinit var languageWithCountry: String
        private set

    fun onLocaleChange(configuration: Configuration) {
        val system = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val localeList = configuration.locales
            (0..<localeList.size()).map { localeList[it] }
        } else {
            @Suppress("DEPRECATION")
            val locale = configuration.locale
            listOf(locale)
        }
        // fcitx translates its own strings too: from Android 13 on the configuration has the app's
        // language, below that only appcompat and the app's prefs know it
        val app = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            emptyList<Locale>()
        } else {
            picked(appContext).let { list -> (0..<list.size()).mapNotNull { list[it] } }
        }
        update(app + system)
    }

    /**
     * [onLocaleChange] once the user picked the app's languages. From Android 13 on, the app's
     * configuration gets them only when the system sends it, after this: here the phone's own
     * languages are read from the system instead, behind the picked ones.
     */
    fun onLocalesPicked(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val app = picked(context).let { list -> (0..<list.size()).mapNotNull { list[it] } }
            val system = context.getSystemService(LocaleManager::class.java).systemLocales
            update(app + (0..<system.size()).map { system[it] })
        } else {
            onLocaleChange(context.resources.configuration)
        }
    }

    private fun update(preferred: List<Locale>) {
        val locales = fcitxLocales(preferred)
        languageWithCountry = locales.firstOrNull() ?: ""
        language = languageWithCountry.substringBefore('_')
        fcitxLocale = locales.joinToString(":")
    }

    /** The `language_COUNTRY` and `language` names fcitx looks for, most preferred first. */
    fun fcitxLocales(preferred: List<Locale>): List<String> {
        val locales = LinkedHashSet<String>()
        for (i in preferred.indices) {
            val it = preferred[i]
            if (it.country.isNotEmpty()) locales.add("${it.language}_${it.country}")
            // fcitx5 only has zh_CN for simplified Chinese and zh_TW for traditional
            if (it.language == "zh") {
                if (it.script == "Hans" && it.country != "CN") {
                    locales.add("zh_CN")
                } else if (it.script == "Hant" && it.country != "TW") {
                    locales.add("zh_TW")
                }
            }
            locales.add("${it.language}")
            // since there is not an `en.mo` file, `en` must be the only locale
            // in order to use default English translation
            if (i == 0 && it.language == "en") break
        }
        return locales.toList()
    }

}