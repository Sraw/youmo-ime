/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.app.LocaleManager
import android.content.res.Configuration
import android.os.LocaleList
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** The locales fcitx and the tables' `Name[...]` keys are looked up by. */
@RunWith(RobolectricTestRunner::class)
class LocalesTest {

    private fun of(vararg tags: String) = Locales.fcitxLocales(tags.map { Locale.forLanguageTag(it) })

    private fun change(tags: String) =
        Locales.onLocaleChange(Configuration().apply { setLocales(LocaleList.forLanguageTags(tags)) })

    @Test
    fun aLanguageWithoutACountryHasNoEmptyCountryEntry() {
        assertEquals(listOf("fr"), of("fr"))
        assertEquals(listOf("zh_CN", "zh"), of("zh-Hans"))
    }

    @Test
    fun chineseOutsideChinaAndTaiwanAlsoTriesFcitxsOwnTranslation() {
        assertEquals(listOf("zh_HK", "zh_TW", "zh"), of("zh-Hant-HK"))
        assertEquals(listOf("zh_SG", "zh_CN", "zh"), of("zh-Hans-SG"))
    }

    @Test
    fun eachLocaleKeepsItsPlaceFollowedByItsLanguage() {
        assertEquals(listOf("zh_HK", "zh", "en_US", "en"), of("zh-HK", "en-US"))
    }

    @Test
    fun englishFirstIsTheOnlyLocaleSoFcitxUsesItsDefault() {
        assertEquals(listOf("en_US", "en"), of("en-US", "zh-CN"))
        assertEquals(listOf("en"), of("en", "zh-CN"))
    }

    @Test
    fun aPickedLanguageAlsoListedByThePhoneIsListedOnce() {
        assertEquals(listOf("zh_CN", "zh", "en_US", "en"), of("zh-CN", "en-US", "zh-CN"))
    }

    @Test
    fun theLanguageIsTheBareLanguageOfTheFirstLocale() {
        change("zh-HK,en-US")
        assertEquals("zh_HK", Locales.languageWithCountry)
        assertEquals("zh", Locales.language)
        assertEquals("zh_HK:zh:en_US:en", Locales.fcitxLocale)
    }

    @Test
    fun aLocaleWithoutACountryIsItsLanguageAlone() {
        change("en")
        assertEquals("en", Locales.languageWithCountry)
        assertEquals("en", Locales.language)
        assertEquals("en", Locales.fcitxLocale)
    }

    @Test
    // Android 6: the configuration's one locale, and the language picked, as each start reads them
    @Config(sdk = [23], application = FcitxApplication::class)
    fun aLanguagePickedInTheAppComesFirstBeforeAndroid13() {
        // the keyboard starts before any activity: appcompat does not know it yet, the app's preferences do
        val picked = AppPrefs.getInstance().internal.appLanguage
        picked.setValue("zh-TW")
        try {
            val configuration = appContext.resources.configuration
            Locales.onLocaleChange(configuration)
            @Suppress("DEPRECATION")
            val phone = configuration.locale
            assertEquals(of("zh-TW", phone.toLanguageTag()).joinToString(":"), Locales.fcitxLocale)
            assertEquals("zh_TW", Locales.languageWithCountry)
        } finally {
            picked.setValue("")
        }
    }

    @Test
    @Config(sdk = [23], application = FcitxApplication::class)
    fun aLanguagePickedInTheAppIsFcitxsAtOnceAndThePhonesAgainWhenItIsPicked() {
        @Suppress("DEPRECATION")
        val phone = appContext.resources.configuration.locale
        AppLanguage.set("zh-TW")
        try {
            assertEquals("zh_TW", Locales.languageWithCountry)
        } finally {
            AppLanguage.set("")
        }
        assertEquals(of(phone.toLanguageTag()).joinToString(":"), Locales.fcitxLocale)
    }

    // Android 13 on, as the pinned SDK: the configuration has a pick only once the system sends it
    @Test
    fun aLanguageJustPickedComesFirstThoughTheConfigurationGetsItLater() {
        val app = RuntimeEnvironment.getApplication()
        app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("zh-TW")
        Locales.onLocalesPicked(app)
        assertEquals("zh_TW", Locales.languageWithCountry)
    }

    @Test
    fun thePhonesLanguagePickedAgainIsReadFromTheSystemNotFromTheConfigurationNotYetSent() {
        val app = RuntimeEnvironment.getApplication()
        // the configuration of the language picked before
        val stale = app.createConfigurationContext(Configuration().apply { setLocales(LocaleList.forLanguageTags("zh-TW")) })
        Locales.onLocalesPicked(stale)
        val system = app.getSystemService(LocaleManager::class.java).systemLocales
        assertEquals(Locales.fcitxLocales((0..<system.size()).map { system[it] }).joinToString(":"), Locales.fcitxLocale)
        assertNotEquals("zh_TW", Locales.languageWithCountry)
    }
}
