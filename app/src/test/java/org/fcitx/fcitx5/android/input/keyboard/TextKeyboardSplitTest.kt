/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import androidx.core.view.allViews
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The letters in two halves on a phone held sideways, G and V on both. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class TextKeyboardSplitTest {

    private fun letters() = TextKeyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), ThemePreset.PixelDark)
        .allViews.filterIsInstance<AltTextKeyView>().joinToString("") { it.mainText.text }.lowercase()

    @After
    fun auto() = ThemeManager.prefs.splitKeyboard.setValue(SplitKeyboardMode.Auto)

    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun aPhoneOnItsSideHasTheMiddleKeysOnBothHalves() {
        assertEquals("qwertyuiopasdfgghjklzxcvvbnm", letters())
        ThemeManager.prefs.splitKeyboard.setValue(SplitKeyboardMode.Off)
        assertEquals("qwertyuiopasdfghjklzxcvbnm", letters())
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port")
    fun uprightItIsOneUnlessAlwaysSplit() {
        assertEquals("qwertyuiopasdfghjklzxcvbnm", letters())
        ThemeManager.prefs.splitKeyboard.setValue(SplitKeyboardMode.Always)
        assertEquals("qwertyuiopasdfgghjklzxcvvbnm", letters())
    }
}
