/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.PanelLayouts.Panel
import org.fcitx.fcitx5.android.input.layout.LayoutImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// the number keyboard's and the symbol panel's user layout
class PanelLayoutsTest {

    private val numbers = CustomChoice("my-num", "大数字", "number", "number", pads = listOf("full", "datetime"))
    private val symbols = CustomChoice("my-sym", "符号", "symbols", "symbols")

    @Test
    fun aLayoutIsForThePanelOfItsFamily() {
        assertEquals(Panel.NUMBER, Panel.of("number"))
        assertEquals(Panel.SYMBOLS, Panel.of("symbols"))
        for (family in listOf("letters", "grouped", "nine", null)) assertNull(Panel.of(family))
    }

    @Test
    fun aNumberBoardIsForThePadsItNamesFullWhereItNamesNone() {
        fun pads(attrs: String) = PanelLayouts.pads(
            LayoutImport.layout("""<keyboard xmlns="https://youmo-ime/layout/1" id="my-num" name="n" family="number" based-on="number"$attrs><row><key id="a" label="a"/></row></keyboard>""")!!,
        )
        assertEquals(listOf("full"), pads(""))
        assertEquals(listOf("phone", "datetime"), pads(""" pads="phone datetime""""))
        assertEquals(emptyList<String>(), PanelLayouts.pads(LayoutImport.layout("""<keyboard xmlns="https://youmo-ime/layout/1" id="my-sym" name="s" family="symbols" based-on="symbols"><row><key id="a" label="a"/></row></keyboard>""")!!))
    }

    @Test
    fun aPanelDrawsTheUsersOfItsFamilyWhereItIsDrawnTheAppsElse() {
        assertSame(numbers, PanelLayouts.drawn(Panel.NUMBER, numbers))
        assertSame(symbols, PanelLayouts.drawn(Panel.SYMBOLS, symbols))
        // none chosen, its file gone, another family's (a pref edited), refused as it is read: the app's
        assertNull(PanelLayouts.drawn(Panel.NUMBER, null))
        assertNull(PanelLayouts.drawn(Panel.NUMBER, symbols))
        assertNull(PanelLayouts.drawn(Panel.NUMBER, numbers.copy(drawn = false)))
        // 读不出，暂用内置的: chosen, not drawn; the app's chosen is no such thing
        assertTrue(PanelLayouts.unread(Panel.NUMBER, "my-num", null))
        assertTrue(PanelLayouts.unread(Panel.NUMBER, "my-num", numbers.copy(drawn = false)))
        assertFalse(PanelLayouts.unread(Panel.NUMBER, "my-num", numbers))
        assertFalse(PanelLayouts.unread(Panel.NUMBER, "", null))
    }

    @Test
    fun aNumberBoardDrawsThePadsItIsFor() {
        assertTrue(PanelLayouts.draws(numbers, NumberPad.FULL))
        assertTrue(PanelLayouts.draws(numbers, NumberPad.DATETIME))
        assertFalse(PanelLayouts.draws(numbers, NumberPad.PHONE))
        assertFalse(PanelLayouts.draws(null, NumberPad.FULL))
    }

    @Test
    fun thePanelsUsingALayoutGoBackOnTheAppsAsItIsDeleted() {
        val chosen = mapOf(Panel.NUMBER to "my-num", Panel.SYMBOLS to "")
        assertEquals(listOf(Panel.NUMBER), PanelLayouts.usedBy(chosen, "my-num"))
        assertEquals(emptyList<Panel>(), PanelLayouts.usedBy(chosen, "my-sym"))
    }

    // L5 review S1: a choice whose file does not read, or is gone, is told where the list shows it
    @Test
    fun aChoiceNotReadIsListedUnderItsPanelOneGoneOnTheAppsCard() {
        val chosen = mapOf(Panel.NUMBER to "my-num", Panel.SYMBOLS to "")
        assertEquals(Panel.NUMBER, PanelLayouts.listedUnder("number", "other", chosen))
        assertEquals("not read, chosen", Panel.NUMBER, PanelLayouts.listedUnder(null, "my-num", chosen))
        assertNull("not read, not chosen", PanelLayouts.listedUnder(null, "other", chosen))
        assertNull("letters, its id chosen by hand", PanelLayouts.listedUnder("letters", "my-num", chosen))
        assertFalse(PanelLayouts.missing(Panel.NUMBER, chosen, mapOf("my-num" to Panel.NUMBER)))
        assertTrue("gone", PanelLayouts.missing(Panel.NUMBER, chosen, emptyMap()))
        assertTrue("another family's now", PanelLayouts.missing(Panel.NUMBER, chosen, mapOf("my-num" to null)))
        assertFalse("the app's chosen", PanelLayouts.missing(Panel.SYMBOLS, chosen, emptyMap()))
    }
}
