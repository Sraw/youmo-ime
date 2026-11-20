/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.daemon.HostDaemon
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.ui.main.AppLanguage
import org.fcitx.fcitx5.android.until
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

/** 界面语言 English on a Chinese phone. Below Android 13 only: Robolectric has no LocaleManager of the app's own languages. */
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

    // changed while the host stayed starting: said it may not have restarted
    @Test
    @Uat("LC-20")
    fun aLanguageChangedWhileTheHostStaysStartingSaysItMayNotHaveRestarted() {
        HostDaemon.testRestarted = false
        try {
            SettingsHarness().use { st ->
                st.activity
                AppLanguage.set("en")
                until("said", { ShadowToast.getTextOfLatestToast() }) { st.idle(); ShadowToast.getTextOfLatestToast() != null }
                assertEquals(appContext.getString(R.string.restart_fcitx_still_starting), ShadowToast.getTextOfLatestToast())
            }
        } finally {
            HostDaemon.testRestarted = true
        }
    }
}
