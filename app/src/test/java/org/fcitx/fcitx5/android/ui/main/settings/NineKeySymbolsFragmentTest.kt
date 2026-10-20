/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class NineKeySymbolsFragmentTest {

    @Test
    fun aKeyShowsItsFirstThreeAndHowManyMore() {
        assertEquals("@ ! \"", NineKeySymbolsFragment.label(listOf("@", "!", "\"")))
        assertEquals("", NineKeySymbolsFragment.label(emptyList()))
        assertEquals("… ： ； +7", NineKeySymbolsFragment.label(listOf("…", "：", "；", "~", ".", "-", "@", "、", "“", "”")))
    }
}
