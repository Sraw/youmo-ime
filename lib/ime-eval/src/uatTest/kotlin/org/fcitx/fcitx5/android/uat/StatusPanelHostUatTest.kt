/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.junit.Test

/**
 * uat/05's status panel toggles, their effect on typing: the Kotlin host with the app's data, the
 * toggle tapped as the panel taps it ([HostReplay.toggle]). The panel itself, its labels and the tap
 * reaching the host, is BarUatTest's.
 */
class StatusPanelHostUatTest {

    @Test
    @Uat("TB-15")
    fun traditionalOnTypesTraditional() = with(HostReplay()) {
        toggle("chttrans")
        type("guojia")
        space()
        expectText("國家")
    }

    @Test
    @Uat("TB-16")
    fun englishPunctuationOnTypesAHalfWidthComma() = with(HostReplay()) {
        toggle("punctuation")
        type(",")
        expectText(",")
    }

    @Test
    @Uat("TB-17")
    fun fullWidthOnTypesAFullWidthDigitButNotInEnglish() = with(HostReplay()) {
        toggle("fullwidth")
        // the digit a swipe up on q sends
        type("1")
        expectText("１")
        ime(InputMethodCatalog.ENGLISH)
        type("1")
        expectText("１1")
    }
}
