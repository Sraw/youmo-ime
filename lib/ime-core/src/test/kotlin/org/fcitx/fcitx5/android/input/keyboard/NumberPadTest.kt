/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.editing.NumberKind
import org.fcitx.fcitx5.android.input.keyboard.LayoutSwitchPolicy.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberPadTest {

    private fun keys(pad: NumberPad) = (pad.left + pad.corner + pad.bottom).filter { it.isNotEmpty() }.toSet()

    @Test
    fun eachFieldGetsThePadOfWhatItTakes() {
        assertNull(NumberPad.of(null))
        // a number field that says no more: the whole pad, a price's point and a minus at hand
        assertEquals(NumberPad.FULL, NumberPad.of(NumberKind.INTEGER))
        // a PIN: the digits alone
        assertEquals(NumberPad.DIGITS, NumberPad.of(NumberKind.PIN))
        assertEquals(emptySet<String>(), keys(NumberPad.DIGITS))
        assertEquals(setOf("-"), keys(NumberPad.of(NumberKind.SIGNED)!!))
        assertEquals(setOf("."), keys(NumberPad.of(NumberKind.DECIMAL)!!))
        assertEquals(setOf("-", "."), keys(NumberPad.of(NumberKind.SIGNED_DECIMAL)!!))
        assertEquals(setOf("+", "*", "#"), keys(NumberPad.of(NumberKind.PHONE)!!) - setOf("-", "(", ")", ","))
        for (kind in listOf(NumberKind.DATE, NumberKind.TIME, NumberKind.DATETIME)) {
            assertEquals(setOf("-", "/", ":", "."), keys(NumberPad.of(kind)!!))
        }
    }

    @Test
    fun everyPadHasItsKeysInTheSamePlaces() {
        for (pad in NumberPad.entries) {
            assertEquals(pad.name, 3, pad.left.size)
            assertEquals(pad.name, 3, pad.bottom.size)
        }
        // the point and the sign where the full pad has its point and its =
        assertEquals(NumberPad.FULL.bottom.indexOf("."), NumberPad.DECIMAL.bottom.indexOf("."))
        assertEquals(NumberPad.FULL.bottom.indexOf("="), NumberPad.SIGNED.bottom.indexOf("-"))
    }

    @Test
    fun eachPadIsALayoutOfItsOwnTheFullOneTheNumberKeyboards() {
        assertEquals("Number", NumberPad.FULL.layout)
        assertEquals(NumberPad.entries.size, NumberPad.layouts.size)
        assertEquals("Number.PHONE", NumberPad.PHONE.layout)
    }

    @Test
    fun aFieldsPadIsShownForTheNumberKeyboard() {
        val pad = FieldPad()
        // a text field: as the switching says
        assertEquals(Target.Keyboard("Number"), pad.shown(Target.Keyboard("Number"), "Text"))
        assertEquals(Target.Unchanged, pad.shown(Target.Unchanged, "Text"))
        assertEquals(Target.SymbolPicker, pad.shown(Target.SymbolPicker, "Text"))
        pad.field = NumberPad.PHONE
        // the 123 key after ABC in a phone field: its pad back
        assertEquals(Target.Keyboard("Number.PHONE"), pad.shown(Target.Keyboard("Number"), "Text"))
        // the full pad up from a text field as a phone field starts: the switching sees the number keyboard shown
        assertEquals(Target.Keyboard("Number.PHONE"), pad.shown(Target.Unchanged, "Number"))
        assertEquals(Target.Unchanged, pad.shown(Target.Unchanged, "Number.PHONE"))
        assertEquals(Target.Unchanged, pad.shown(Target.Keyboard("Number"), "Number.PHONE"))
        assertEquals(Target.Keyboard("Text"), pad.shown(Target.Keyboard("Text"), "Number.PHONE"))
        // the switching knows every pad as the number keyboard, never remembers one
        assertEquals("Number", pad.known("Number.PHONE"))
        assertEquals("Number", pad.known("Number"))
        assertEquals("T9", pad.known("T9"))
        pad.field = null
        // a text field after a number field, the switching having seen the number keyboard: the full one
        assertEquals(Target.Keyboard("Number"), pad.shown(Target.Unchanged, "Number.DIGITS"))
    }

    @Test
    fun theSwitchingThroughThePadNeverRemembersOne() {
        val pad = FieldPad().apply { field = NumberPad.DIGITS }
        val policy = LayoutSwitchPolicy(setOf("Text") + NumberPad.layouts, "Text", "Symbol")
        val outcome = policy.switchTo("Number", "Symbol", pad.known("Text"), remember = true)
        assertEquals("Number", outcome.remember)
        assertEquals(Target.Keyboard("Number.DIGITS"), pad.shown(outcome.target, "Text"))
    }
}
