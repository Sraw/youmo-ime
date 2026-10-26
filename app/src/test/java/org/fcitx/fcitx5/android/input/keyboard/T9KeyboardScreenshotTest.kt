/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UAT T9-01, on the JVM: the nine keys in the whole keyboard (bar, background, the space key's
 * input method name), against the golden images under src/test/screenshots. Record them again
 * with `-Proborazzi.test.record=true` when the look changes on purpose.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class T9KeyboardScreenshotTest {

    @Test
    @Uat("T9-01")
    fun theNineKeysLight() = check(ThemePreset.PixelLight, "t9_light")

    @Test
    @Uat("T9-01")
    fun theNineKeysDark() = check(ThemePreset.PixelDark, "t9_dark")

    private fun check(theme: Theme, name: String) = KeyboardHarness(theme, KeyboardHarness.T9).use { h ->
        h.start()
        val labels = h.texts()
        // left column's punctuation is the column; the keys: 1 to 9 with their letters, 重输, 0, 符, 123
        for (d in '1'..'9') assertTrue("$d in $labels", d.toString() in labels)
        for (l in listOf("ABC", "DEF", "WXYZ", "重输", "0", "符", "123")) assertTrue("$l in $labels", l in labels)
        assertTrue("space names the input method: $labels", "Pinyin (9 keys)" in labels)
        assertTrue("中/英" in labels)
        h.capture("T9KeyboardScreenshotTest/$name")
    }
}
