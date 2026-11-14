/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.data.theme

import android.os.Parcel
import android.os.Parcelable
import org.fcitx.fcitx5.android.FcitxApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A theme through a parcel (a screen's saved state) comes back the same, its colours each where they were. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ThemeParcelTest {

    private fun <T : Parcelable> again(theme: T): Theme {
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(theme, 0)
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION")
            return parcel.readParcelable(Theme::class.java.classLoader)!!
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun aBuiltinThemesPaletteGoesThroughByName() {
        // all 24 different, so two swapped would show
        val nord = TestThemes.NordDark
        val palette = nord.palette.copy(keyBorderColor = 0x11223344, keyHintColor = 0x22334455, toolbarIconColor = 0x33445566)
        val theme = nord.copy(palette = palette)
        val back = again(theme) as Theme.Builtin
        assertEquals(theme, back)
        assertEquals(palette, back.palette)
        assertEquals(palette.keyTextColor, back.keyTextColor)
    }

    @Test
    fun aCustomThemesColoursAreItsLooks() {
        val custom = TestThemes.NordDark.toCustom()
        val back = again(custom) as Theme.Custom
        assertEquals(custom, back)
        assertEquals(custom.look.palette, back.palette)
        assertEquals(custom.look.palette.genericActiveForegroundColor, back.genericActiveForegroundColor)
    }
}
