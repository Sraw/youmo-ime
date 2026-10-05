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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val locales = LinkedHashSet<String>()
            val localeList = configuration.locales
            for (i in 0..<localeList.size()) {
                val it = localeList[i]
                locales.add("${it.language}_${it.country}")
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
            languageWithCountry = locales.firstOrNull() ?: ""
            language = languageWithCountry.substringBefore(':')
            fcitxLocale = locales.joinToString(":")
        } else {
            @Suppress("DEPRECATION")
            val it = configuration.locale
            languageWithCountry = "${it.language}_${it.country}"
            language = it.language
            fcitxLocale = "$languageWithCountry:$language"
        }
    }

}