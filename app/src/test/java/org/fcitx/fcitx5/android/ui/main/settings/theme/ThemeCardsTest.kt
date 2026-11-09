/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.deriveCustomNoBackground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The theme lists' cards (060-render-p3 T3): a built-in theme of two versions one card. */
class ThemeCardsTest {

    private val mine = TestThemes.PixelDark.deriveCustomNoBackground("mine")
    private val themes = listOf(mine) + TestThemes.builtins.map { it.second }

    private fun ThemeCards.names() = shown.map { it.name }

    @Test
    fun aPairIsOneCardInTheListsOrder() {
        assertEquals(
            listOf("mine", "monet#light", "material#light", "pixel#light", "nord#light", "deep-blue", "monokai", "amoled-black"),
            ThemeCards(themes).names(),
        )
    }

    // the dark slot's dialog: each pair's dark version first, a theme of one as it is
    @Test
    fun aSlotsDialogShowsItsVersion() {
        assertEquals(
            listOf("mine", "monet#dark", "material#dark", "pixel#dark", "nord#dark", "deep-blue", "monokai", "amoled-black"),
            ThemeCards(themes, dark = true).names(),
        )
    }

    @Test
    fun aVersionShownOnItsCardTheOtherSwitchedTo() {
        val cards = ThemeCards(themes)
        assertEquals(3, cards.show(TestThemes.PixelDark))
        assertEquals("pixel#dark", cards.shown[3].name)
        assertEquals(TestThemes.PixelLight, cards.other(cards.shown[3]))
        assertEquals(3, cards.indexOf(TestThemes.PixelLight))
        cards.switch(3)
        assertEquals("pixel#light", cards.shown[3].name)
        // a card of one version: nothing to switch to
        assertNull(cards.other(mine))
        assertFalse(cards.switch(0))
        assertEquals(-1, cards.show(TestThemes.TransparentDark))
        assertEquals(-1, cards.indexOf(null))
    }
}
