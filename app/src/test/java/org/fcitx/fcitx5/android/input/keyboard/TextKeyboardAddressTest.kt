/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The bottom row's keys either side of the space, as the field is. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class TextKeyboardAddressTest {

    private val keyboard = TextKeyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), TestThemes.PixelDark)

    private val ids = listOf(R.id.button_comma, R.id.button_slash, R.id.button_at, R.id.button_period, R.id.button_dot, R.id.button_domain)

    private fun shown() = ids.filter { keyboard.laidOut().shownKeyWithId(it) != null }

    @Test
    fun anAddressFieldHasWhatAddressesAreMadeOfInPlaceOfTheCommaAndFullStop() {
        assertEquals(listOf(R.id.button_comma, R.id.button_period), shown())
        keyboard.address = AddressKeys.URL
        // a full stop as it is too: www., co.uk
        assertEquals(listOf(R.id.button_slash, R.id.button_dot, R.id.button_domain), shown())
        keyboard.address = AddressKeys.EMAIL
        assertEquals(listOf(R.id.button_at, R.id.button_dot, R.id.button_domain), shown())
        keyboard.address = AddressKeys.NONE
        assertEquals(listOf(R.id.button_comma, R.id.button_period), shown())
    }
}
