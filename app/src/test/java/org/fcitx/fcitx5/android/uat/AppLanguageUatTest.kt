/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 界面语言 (uat/14 ST-25), the keyboard's half: on a Chinese phone, English picked for the app, the
 * keyboard's own words English. Below Android 13, where appcompat keeps the pick (Robolectric has
 * no LocaleManager of the app's own languages to set on 13 and later).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "zh-rCN-w393dp-h851dp-port-440dpi", sdk = [29])
class AppLanguageUatTest {

    @After
    fun close() {
        AppPrefs.getInstance().internal.appLanguage.setValue("")
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    @Uat("ST-25")
    fun englishPickedTheKeyboardsOwnWordsAreEnglish() {
        KeyboardHarness().use { h ->
            h.start()
            assertTrue(h.texts().toString(), h.described("表情") != null)
        }
        AppPrefs.getInstance().internal.appLanguage.setValue("en")
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        KeyboardHarness().use { h ->
            h.start()
            assertTrue(h.texts().toString(), h.described("Emoji") != null)
        }
    }
}
