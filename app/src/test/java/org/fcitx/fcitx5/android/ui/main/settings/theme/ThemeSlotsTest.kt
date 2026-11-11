/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThemeSlotsTest {

    @Test
    fun followingTheSystemAThemeOfOneVersionTakesBothSlots() {
        val pair = TestThemes.PixelLight to TestThemes.PixelDark
        assertEquals(pair, ThemeSlots.picked(TestThemes.PixelDark, pair, following = true))
        assertEquals(TestThemes.AMOLEDBlack to TestThemes.AMOLEDBlack, ThemeSlots.picked(TestThemes.AMOLEDBlack, null, following = true))
        assertNull(ThemeSlots.picked(TestThemes.AMOLEDBlack, null, following = false))
    }
}
