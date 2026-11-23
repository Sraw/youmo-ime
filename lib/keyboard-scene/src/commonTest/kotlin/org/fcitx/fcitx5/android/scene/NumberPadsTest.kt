/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.keys.ScrollItem
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.skin.assertRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The number pads of 0.6.1 (user 2026-11-13): ⌫ at the top right of every pad, as on the nine keys; a place a pad has
 * no key for drawn nothing and taking no touch, the keyboard's fill showing, the layout not rearranged. And (2026-11-14,
 * 061-layout-survey Q1-A, Q2, Q3-1, Q4-a) the nine keys' frame, the bottom row's first two places as on the letter boards:
 * 符 first, the way back where 123 is; the symbol panel the other way round, 返回 then 123.
 */
class NumberPadsTest {

    private fun scene(pad: String, form: String): Scene {
        val e = Forms.env("number", Forms.all.getValue(form))
        return Forms.scene("number", e.copy(input = e.input.copy(pad = pad)))
    }

    private fun key(s: Scene, id: String) = s.keys.single { it.id == id }

    // the key right of 9 on a pad that has one there; the other pads' place is blank
    private val corners = mapOf("full" to "=")

    // a key's right edge as placed, the last key's a sum of widths
    private val eps = 1e-6

    // every pad, each with what it is: the rows of the tests below, each in every form
    private val pads = listOf(
        "full" to "the 123 key's pad, = right of 9",
        "phone" to "a dialler's, blank right of 9",
        "datetime" to "a date's or time's",
    ).flatMap { (pad, why) -> Forms.all.keys.map { form -> Triple(pad, form, why) } }

    // what ⌫'s place on the nine keys' frame (everyPadSitsOnTheNineKeysFrame pins it there, in the row of 1 2 3) leaves
    // to say: nothing right of it, the space still right of 6, and the key it had there now right of 9
    @Test
    fun backspaceIsTopRightOnEveryPadAsOnTheNineKeys() = assertRows(pads, { (pad, form, why) -> "$pad $form: $why" }) { (pad, form, _) ->
        val s = scene(pad, form)
        val back = key(s, "backspace").bounds
        assertEquals(s.keys.maxOf { it.bounds.right }, back.right, eps, "rightmost")
        assertEquals(key(s, "space").bounds.top, key(s, "4").bounds.top, "the space stays right of 6")
        corners[pad]?.let { id ->
            val nine = key(s, "9").bounds
            val corner = s.keys.single { it.id == id && it.bounds.left >= nine.right }.bounds
            assertEquals(nine.top, corner.top, "$id right of 9, where ⌫ was")
        }
    }

    // the frame the pads are held to: the nine keys' ⌫ in the top row, the row of 1 2 3, in each form
    @Test
    fun theNineKeysBackspaceIsInTheTopRow() = assertRows(
        Forms.all.keys.toList().map { it to "in the $it form" },
        { (form, why) -> "$form: $why" },
    ) { (form, _) ->
        val nine = Forms.scene("nine", Forms.env("nine", Forms.all.getValue(form)))
        val back = key(nine, "backspace").bounds
        assertEquals(nine.keys.minOf { it.bounds.top }, back.top, "the top row")
        assertEquals(key(nine, "3").bounds.top, back.top, "the row of 1 2 3")
    }

    // each pad on the nine keys' frame, in each form: a key the nine keys have a match for sits exactly on it, so going
    // 123 and back from the nine keys moves none of them
    @Test
    fun everyPadSitsOnTheNineKeysFrame() = assertRows(
        pads,
        { (pad, form, why) -> "$pad $form: $why" },
    ) { (pad, form, _) ->
        val s = scene(pad, form)
        val nine = Forms.scene("nine", Forms.env("nine", Forms.all.getValue(form)))
        // the pad's key to the nine keys' in its place: the digits, ⌫, Return, 符, and 返回 where 123 is
        val match = (1..9).associate { "$it" to "$it" } +
            mapOf("backspace" to "backspace", "enter" to "enter", "symbols" to "symbols", "back" to "numbers")
        for ((id, there) in match) {
            val b = key(s, id).bounds
            val n = key(nine, there).bounds
            assertEquals(n.left, b.left, eps, "$id's left on the nine keys' $there")
            assertEquals(n.right, b.right, eps, "$id's right on the nine keys' $there")
            assertEquals(n.top, b.top, eps, "$id's top on the nine keys' $there")
            assertEquals(n.bottom, b.bottom, eps, "$id's bottom on the nine keys' $there")
        }
        // 0 under 8, the digits' column as on a phone's: wider for the lock beside it, over 8's middle
        val eight = key(s, "8").bounds
        val zero = key(s, "0").bounds
        assertTrue((eight.left + eight.right) / 2 in zero.left..zero.right, "0 under 8")
    }

    // 符 as the letter boards' 符 (its words, its function face: drawn the same), the way back saying 返回 as the panels' does
    @Test
    fun theBottomRowStartsAsTheLetterBoardsWith符ThenTheWayBack() = assertRows(
        Forms.all.keys.toList().map { it to "drawn the same in the $it form" },
        { (form, why) -> "$form: $why" },
    ) { (form, _) ->
        val s = scene("full", form)
        val nine = Forms.scene("nine", Forms.env("nine", Forms.all.getValue(form)))
        val bottom = s.keys.filter { it.bounds.top == key(s, "0").bounds.top }.sortedBy { it.bounds.left }.map { it.id }
        assertEquals(listOf("symbols", "back", "lock", "0", ".", "enter"), bottom, "the bottom row, left to right")
        val sym = key(s, "symbols")
        val letters = key(nine, "symbols")
        assertEquals(letters.label, sym.label, "符's words")
        assertEquals(letters.kind, sym.kind, "a function key, as the letter boards' 符")
        assertEquals(letters.ops(false), sym.ops(false), "drawn as the nine keys' 符, in the same place")
        val back = key(s, "back")
        assertEquals(Forms.block.texts.back, back.label, "返回, the panels' word")
        assertEquals(key(nine, "numbers").kind, back.kind, "a function key as the 123 it stands for")
    }

    // the symbol panel: 返回 first (where 符 was), 123 second as on the letter boards (its words, a function key), the lock after
    @Test
    fun theSymbolPanelsBottomRowIsBackThen123ThenTheLock() = assertRows(
        Forms.all.keys.toList().map { it to "in the $it form" },
        { (form, why) -> "$form: $why" },
    ) { (form, _) ->
        val s = Forms.scene("symbols", Forms.env("symbols", Forms.all.getValue(form)))
        val qwerty = Forms.scene("qwerty", Forms.env("qwerty", Forms.all.getValue(form)))
        assertEquals(listOf("back", "numbers", "lock", "space", "backspace", "enter"), s.keys.sortedBy { it.bounds.left }.map { it.id }, "left to right")
        val n = key(s, "numbers")
        val letters = key(qwerty, "numbers")
        assertEquals(letters.label, n.label, "123's words")
        assertEquals(letters.kind, n.kind, "a function key, as the letter boards' 123")
        assertEquals(letters.a11y, n.a11y, "read as the letter boards' 123")
        assertEquals(Forms.block.texts.back, key(s, "back").label, "返回")
    }

    // every way back says 返回: the number pads', the emoji's and the emoticons' as the symbol panel's (was ABC)
    @Test
    fun everyWayBackSays返回() = assertRows(
        listOf(
            "number" to "the number pads' (each pad, and the one a number field opens with)",
            "emoji" to "the emoji panel's",
            "emoticon" to "the emoticon panel's",
            "symbols" to "the symbol panel's, which said it already",
        ),
        { (board, why) -> "$board: $why" },
    ) { (board, _) ->
        val s = Forms.scene(board, Forms.env(board, Forms.block))
        assertEquals(Forms.block.texts.back, key(s, "back").label)
        assertEquals("返回", Forms.block.texts.back, "the test's words are the app's Chinese")
    }

    // nothing drawn at a blank place: the date pad's ground (Scene.base) is the full pad's, which has no blank, in each form;
    // and a finger there presses nothing
    @Test
    fun aBlankPlaceDrawsNothingAndTakesNoTouch() = assertRows(
        // its other blanks are two of the bottom row: the place right of 9 is the one probed
        listOf("phone" to "a dialler's", "datetime" to "a date's or time's").flatMap { (pad, why) -> Forms.all.keys.map { form -> Triple(pad, form, why) } },
        { (pad, form, why) -> "$pad $form: $why" },
    ) { (pad, form, _) ->
        val s = scene(pad, form)
        assertEquals(scene("full", form).base, s.base, "the keyboard's fill alone, as the full pad's")
        val nine = key(s, "9").bounds
        val back = key(s, "backspace").bounds
        assertNull(s.keyAt((back.left + back.right) / 2, (nine.top + nine.bottom) / 2), "right of 9, ⌫'s old place, presses nothing")
    }

    // a board copied from the app's when a PIN's, a decimal's and a signed field's pads were their own: their keys and
    // blanks (when="pad=digits|…") never shown, each pad it is for laid out as 0.6.2's app's was
    @Test
    fun anOlderCopyWithTheMergedPadsConditionsIsLaidOutAsTheAppsPads() = assertRows(
        listOf("full" to "123's and every number field's", "phone" to "a dialler's", "datetime" to "a date's, not its but drawn all the same"),
        { (pad, why) -> "$pad: $why" },
    ) { (pad, _) ->
        val old = LayoutReader.layout(testData("../../../../../uat/fixtures/layouts/uat-number-1.0.layout")).ok()
        val e = Forms.env("number", Forms.block).let { it.copy(input = it.input.copy(pad = pad)) }
        val mine = SceneBuilder(Fixtures.materialLight, e, FakeMeasurer).build(old, Forms.fragments).ok()
        val app = SceneBuilder(Fixtures.materialLight, e, FakeMeasurer).build(LayoutReader.layout(testData("layout-check/number-062.xml")).ok(), Forms.fragments).ok()
        assertEquals(app.keys.map { it.id to it.bounds }, mine.keys.map { it.id to it.bounds })
    }

    // a pack's number.xml of 0.6.0, its blanks written with a look: still read, drawn nothing all the same (one rule for
    // every layout, the app's or a pack's)
    @Test
    fun aGapsLookOfAnOldLayoutReadsAndDrawsNothing() {
        val text = "<keyboard xmlns=\"https://youmo-ime/layout/1\" id=\"number\" name=\"数字\" family=\"number\" columns=\"3\">" +
            "<row><key id=\"1\" label=\"1\"/><gap look=\"function\" class=\"alt-text\"/><key id=\"2\" label=\"2\"/></row></keyboard>"
        fun scene(t: String) = SceneBuilder(Fixtures.materialLight, Forms.env("number", Forms.block), FakeMeasurer).build(LayoutReader.layout(t).ok(), emptyMap()).ok()
        val s = scene(text)
        assertEquals(scene(text.replace(" look=\"function\" class=\"alt-text\"", "")).base, s.base, "the keyboard's fill alone")
        assertEquals(listOf("1", "2"), s.keys.map { it.id })
    }

    // the marks are the scroll column's, left of 1 4 7 down the top three rows; = right of 9 on 123's pad alone; the lock on every pad
    @Test
    fun theMarksAreAScrollColumnAndTheLockIsOnEveryPad() = assertRows(pads, { (pad, form, why) -> "$pad $form: $why" }) { (pad, form, _) ->
        val s = scene(pad, form)
        val side = s.slots.single { it.kind == "scroll" }
        assertEquals("number", side.list)
        assertEquals(key(s, "1").bounds.top, side.bounds.top, eps, "from the row of 1")
        assertEquals(key(s, "7").bounds.bottom, side.bounds.bottom, eps, "to the row of 7")
        assertEquals(key(s, "4").bounds.left, side.bounds.right, eps, "left of 4")
        assertEquals(pad == "full", s.keys.any { it.id == "=" }, "= on 123's pad alone")
        assertEquals(pad != "phone", s.keys.any { it.id == "." }, ". but on the dialler's")
        assertEquals("panel-lock", key(s, "lock").actions["send"], "the lock")
        for (id in listOf("+", "-", "*", "/", ",", "#", "(", ")", ":")) assertTrue(s.keys.none { it.id == id }, "$id is the column's")
    }

    // where no view of the app's draws it (a card, the web): a command item its icon, whole and in proportion inside its
    // item's place; a horizontal column's items side by side
    @Test
    fun aColumnsCommandIsItsIconInItsPlaceAcrossOrDown() {
        val items = mapOf("number" to listOf(ScrollItem.Text("+"), ScrollItem.Command("select_to_start")))
        val e = Forms.env("number", Forms.block).copy(scrollItems = items)
        val down = Forms.scene("number", e).slots.single { it.kind == "scroll" }
        // narrow: an item's place a third of a tenth of the width, narrower than the icon
        val text = "<keyboard xmlns=\"https://youmo-ime/layout/1\" id=\"n\" name=\"n\" family=\"number\" columns=\"10\">" +
            "<row><slot kind=\"scroll\" list=\"number\" direction=\"horizontal\" show=\"3\"/><key id=\"1\" label=\"1\" width=\"9\"/></row></keyboard>"
        val across = SceneBuilder(Fixtures.materialLight, e, FakeMeasurer).build(LayoutReader.layout(text).ok(), emptyMap()).ok().slots.single()
        assertRows(listOf(down to true, across to false), { (_, vertical) -> if (vertical) "down the pad's column" else "across a narrow one" }) { (slot, vertical) ->
            val b = slot.bounds
            val place = if (vertical) {
                val h = b.height / slot.show
                Box(b.left, b.top + h, b.right, b.top + 2 * h) to Box(b.left, b.top, b.right, b.top + h)
            } else {
                val w = b.width / slot.show
                Box(b.left + w, b.top, b.left + 2 * w, b.bottom) to Box(b.left, b.top, b.left + w, b.bottom)
            }
            val icon = slot.ops.filterIsInstance<Op.Icon>().single()
            assertEquals("select-to-start", icon.name)
            val (second, first) = place
            assertTrue(icon.box.left >= second.left - eps && icon.box.right <= second.right + eps, "${icon.box} across in $second")
            assertTrue(icon.box.top >= second.top - eps && icon.box.bottom <= second.bottom + eps, "${icon.box} down in $second")
            assertEquals(icon.box.width, icon.box.height, eps, "a square icon stays square")
            val plus = slot.ops.filterIsInstance<Op.Text>().single { it.text == "+" }
            assertTrue(plus.x in first.left..first.right && plus.baseline in first.top..first.bottom, "+ at ${plus.x}, ${plus.baseline} in $first")
        }
    }
}
