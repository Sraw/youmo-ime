/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.editing.NumberKind
import org.fcitx.fcitx5.android.input.keyboard.LayoutSwitchPolicy.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberPadTest {

    private fun keys(pad: NumberPad) = (pad.left + pad.besideNine + pad.bottom).filter { it.isNotEmpty() }.toSet()

    @Test
    fun eachFieldGetsThePadOfWhatItTakes() = assertRows(
        "a text field: the letters" to { assertNull(NumberPad.of(null)) },
        // an amount declared decimal takes a minus: what a field declares is no list of what it takes
        "any number field, decimal, signed or a PIN: the whole pad, a point and a minus at hand" to {
            assertEquals(NumberPad.FULL, NumberPad.of(NumberKind.NUMBER))
            assertEquals(setOf("+", "-", "*", "/", ",", "=", "."), keys(NumberPad.FULL))
        },
        "a phone field: a dialler's signs" to {
            assertEquals(setOf("+", "*", "#"), keys(NumberPad.of(NumberKind.PHONE)!!) - setOf("-", "(", ")", ","))
        },
        "a date, a time: their separators" to { assertEquals(setOf("-", "/", ":", "."), keys(NumberPad.of(NumberKind.DATETIME)!!)) },
    )

    @Test
    fun everyPadHasItsKeysInTheSamePlaces() {
        for (pad in NumberPad.entries) {
            assertEquals(pad.name, 3, pad.left.size)
            assertEquals(pad.name, 3, pad.bottom.size)
        }
        // the point where the full pad has it
        assertEquals(NumberPad.FULL.bottom.indexOf("."), NumberPad.DATETIME.bottom.indexOf("."))
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
        assertEquals(Target.Keyboard("Number"), pad.shown(Target.Unchanged, "Number.DATETIME"))
    }

    @Test
    fun theSwitchingThroughThePadNeverRemembersOne() {
        val pad = FieldPad().apply { field = NumberPad.DATETIME }
        val policy = LayoutSwitchPolicy(setOf("Text") + NumberPad.layouts, "Text", "Symbol")
        val outcome = policy.switchTo("Number", "Symbol", pad.known("Text"), remember = true)
        assertEquals("Number", outcome.remember)
        assertEquals(Target.Keyboard("Number.DATETIME"), pad.shown(outcome.target, "Text"))
    }

    @Test
    fun eachPadIsNamedAsALayoutsPadsNameIt() {
        assertEquals(listOf("full", "phone", "datetime"), NumberPad.entries.map { it.id })
        assertEquals(NumberPad.PHONE, NumberPad.shownBy("Number.PHONE"))
        assertEquals(NumberPad.FULL, NumberPad.shownBy("Number|my-num@1-2"))
        assertNull(NumberPad.shownBy("Text|my-num@1-2"))
    }

    @Test
    fun theUsersNumberBoardIsShownForThePadsItIsForTheAppsForTheRest() {
        // a board for 123's pad and the phone's
        val mine = CustomChoice("my-num", "大数字", "number", "number", stamp = "1-2", pads = listOf("full", "phone"))
        val pad = FieldPad().apply { numbers = mine }
        val full = "Number|my-num@1-2"
        // the 123 key in a text field: the user's
        assertEquals(Target.Keyboard(full), pad.shown(Target.Keyboard("Number"), "Text"))
        // shown already: as it is; the switching knows it as the number keyboard
        assertEquals(Target.Unchanged, pad.shown(Target.Unchanged, full))
        assertEquals("Number", pad.known(full))
        // a phone field: the user's phone pad; a date's, which it is not for, the app's; a decimal field's, the full one: the user's
        pad.field = NumberPad.PHONE
        assertEquals(Target.Keyboard("Number.PHONE|my-num@1-2"), pad.shown(Target.Unchanged, full))
        pad.field = NumberPad.DATETIME
        assertEquals(Target.Keyboard("Number.DATETIME"), pad.shown(Target.Keyboard("Number"), "Text"))
        pad.field = NumberPad.of(NumberKind.NUMBER)
        assertEquals(Target.Keyboard(full), pad.shown(Target.Keyboard("Number"), "Text"))
        // the file replaced (another stamp): shown anew; the app's again where none is chosen
        pad.field = null
        pad.numbers = mine.copy(stamp = "3-4")
        assertEquals(Target.Keyboard("Number|my-num@3-4"), pad.shown(Target.Unchanged, full))
        pad.numbers = null
        assertEquals(Target.Keyboard("Number"), pad.shown(Target.Unchanged, full))
        // the letters, whatever draws them, left to the switching
        assertEquals(Target.Unchanged, pad.shown(Target.Unchanged, "Text|my-qwerty@1-2"))
    }
}
