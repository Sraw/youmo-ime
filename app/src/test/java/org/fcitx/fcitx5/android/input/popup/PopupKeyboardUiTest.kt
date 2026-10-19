/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.ceil

/**
 * A key of the long-press popup is typed where it is drawn. With more columns than a short top
 * row fills (13 entries: 5 columns, 3 rows), the empty slots fall at both ends when the popup is
 * centred over its key; laid out by gravity, the top row's keys were drawn a column or two off
 * from where the finger selects them, and the neighbour was typed.
 */
@RunWith(RobolectricTestRunner::class)
class PopupKeyboardUiTest {

    private val keyWidth = 40
    private val keyHeight = 50
    private val outer = Rect(0, 0, 1000, 600)

    private fun popup(count: Int, trigger: Rect): PopupKeyboardUi =
        popup(Array(count) { ('a' + it).toString() }, trigger)

    private fun popup(keys: Array<String>, trigger: Rect, outerBounds: Rect = outer, minKeyWidth: Int = keyWidth): PopupKeyboardUi {
        return PopupKeyboardUi(
            ctx = RuntimeEnvironment.getApplication(),
            theme = ThemePreset.PixelDark,
            outerBounds = outerBounds,
            triggerBounds = trigger,
            radius = 4f,
            minKeyWidth = minKeyWidth,
            keyHeight = keyHeight,
            popupHeight = 100,
            keys = keys,
            labels = keys
        )
    }

    private fun View.label(): String? = ((this as? ViewGroup)?.getChildAt(0) as? TextView)?.text?.toString()

    /** Every key drawn, by its label, at (row from the bottom, column), as laid out. */
    private fun drawn(ui: PopupKeyboardUi): Map<String, Pair<Int, Int>> {
        val root = ui.root
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        root.measure(unspecified, unspecified)
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val rows = root.childCount
        val keys = mutableMapOf<String, Pair<Int, Int>>()
        for (r in 0 until rows) {
            val row = root.getChildAt(r) as ViewGroup
            for (k in 0 until row.childCount) {
                val key = row.getChildAt(k)
                val label = key.label() ?: continue
                // the top row is added first
                keys[label] = (rows - 1 - r) to key.left / keyWidth
            }
        }
        return keys
    }

    // the middle of a cell as drawn: rows of keyHeight from the container's top, row 0 the bottom one
    private fun PopupKeyboardUi.select(row: Int, column: Int, rows: Int): KeyAction? {
        onChangeFocus((column + 0.5f) * keyWidth, (rows - 1 - row + 0.5f) * keyHeight)
        return onTrigger()
    }

    private fun assertTypedWhereDrawn(count: Int, trigger: Rect) {
        val ui = popup(count, trigger)
        val keys = drawn(ui)
        assertEquals("every key is drawn", count, keys.size)
        val rows = ui.root.childCount
        keys.forEach { (label, cell) ->
            assertEquals(
                "$count keys: the key drawn at $cell is the one typed there",
                KeyAction.FcitxKeyAction(label),
                ui.select(cell.first, cell.second, rows)
            )
        }
    }

    @Test
    fun aCentredPopupTypesTheKeyUnderTheFinger() {
        val centre = Rect(480, 500, 520, 550)
        // the user's own list (PopupOverrides) can be any length
        for (count in 1..30) assertTypedWhereDrawn(count, centre)
    }

    @Test
    fun aPopupAtAnEdgeTypesTheKeyUnderTheFinger() {
        // a short top row: one empty slot (7), two (13, 18), three (17)
        for (count in listOf(7, 13, 17, 18)) {
            assertTypedWhereDrawn(count, Rect(0, 500, 40, 550))
            assertTypedWhereDrawn(count, Rect(960, 500, 1000, 550))
        }
    }

    @Test
    fun theTopRowKeepsItsEmptySlots() {
        // 13 keys, 5 columns: the top row's 3 are drawn in the middle columns, not packed to one side
        val keys = drawn(popup(13, Rect(480, 500, 520, 550)))
        assertEquals(setOf(1, 2, 3), keys.values.filter { it.first == 2 }.map { it.second }.toSet())
    }

    /** The long press and a letter's swipe (its first) type a pick the same way. */
    @Test
    fun oneCharacterIsAKeyAndAnythingLongerIsText() {
        assertEquals(KeyAction.FcitxKeyAction("x"), PopupKeyboardUi.keyAction("x"))
        assertEquals(KeyAction.FcitxKeyAction("é"), PopupKeyboardUi.keyAction("é"))
        // one code point, two chars
        assertEquals(KeyAction.FcitxKeyAction("😀"), PopupKeyboardUi.keyAction("😀"))
        assertEquals(KeyAction.CommitAction("Émile"), PopupKeyboardUi.keyAction("Émile"))
        // spells a key name, still text
        assertEquals(KeyAction.CommitAction("Tab"), PopupKeyboardUi.keyAction("Tab"))
        // one letter to the eye (Yiddish ײַ), two code points: text
        assertEquals(KeyAction.CommitAction("\u05F2\u05B7"), PopupKeyboardUi.keyAction("\u05F2\u05B7"))
    }

    private fun PopupKeyboardUi.labels(): List<TextView> {
        val rows = (0 until root.childCount).map { root.getChildAt(it) as ViewGroup }
        return rows.flatMap { row -> (0 until row.childCount).mapNotNull { ((row.getChildAt(it) as? ViewGroup)?.getChildAt(0) as? TextView) } }
    }

    /** `.com`'s long press: its labels each fitted to a fixed width came out at four sizes. */
    @Test
    fun keysOfLabelsOfManyLengthsAreOneWidthAndOneTextSize() {
        val domains = arrayOf(".com", ".cn", ".net", ".org")
        val ui = popup(domains, Rect(480, 500, 520, 550))
        val labels = ui.labels()
        assertEquals(4, labels.size)
        assertEquals(1, labels.map { it.textSize }.toSet().size)
        // wide enough for the widest, so none is made smaller
        assertTrue(labels.all { it.paint.measureText(it.text.toString()) < ui.keyWidth })
        assertTrue(ui.keyWidth >= keyWidth)
    }

    /**
     * AutoScaleTextView draws a label too wide for its view smaller, its text size unchanged: one
     * text size held for the old keys too, `.com` too wide for its 38 dp. Under a bubble narrower
     * than any label, every label fits its key with its padding (6 dp a side, at 1 dp = 1 px) and is
     * measured as wide as its text: none is drawn smaller.
     */
    @Test
    fun everyLabelFitsItsKeySoNoneIsDrawnSmaller() {
        val domains = arrayOf(".com", ".cn", ".net", ".org")
        val ui = popup(domains, Rect(480, 500, 520, 550), minKeyWidth = 1)
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        ui.root.measure(unspecified, unspecified)
        for (label in ui.labels()) {
            val text = label.paint.measureText(label.text.toString())
            assertTrue("${label.text}: $text in ${ui.keyWidth}", text + 12 <= ui.keyWidth)
            assertEquals("${label.text}", ceil(text).toInt(), label.measuredWidth)
        }
    }

    /** Where the popup starts and ends across, as PopupComponent places it over [trigger]. */
    private fun PopupKeyboardUi.across(trigger: Rect, columns: Int) =
        (trigger.left + offsetX).let { it to it + columns * keyWidth }

    // keys as wide as the bubble may be wider than the key pressed: at an edge no column centred over it fits
    @Test
    fun aPopupOfKeysWiderThanItsTriggerStaysOnScreen() {
        // nine keys' 9: five keys of a fifth of the width, over a key a little wider
        val nine = Rect(600, 500, 820, 550)
        val ui = popup(Array(5) { ('a' + it).toString() }, nine, minKeyWidth = 220)
        assertEquals(200, ui.keyWidth)
        assertEquals(0 to 1000, ui.across(nine, 5))
        // moved in by 10: the first key starts at the trigger's left, not 10 right of it, and is still under the finger
        assertEquals(0, ui.focusedKeyLeft)
        ui.changeFocus(nine.width() / 2f, nine.height() / 2f)
        assertEquals(KeyAction.FcitxKeyAction("a"), ui.onTrigger())
        // a long word over Q, the key at either edge
        for (q in listOf(Rect(0, 500, 40, 550), Rect(960, 500, 1000, 550))) {
            val (left, right) = popup(arrayOf("q", "hello"), q, minKeyWidth = 80).across(q, 2)
            assertTrue("$q: $left to $right", left >= 0 && right <= 1000)
        }
    }

    // pressed before the popups' root is laid out, its bounds still empty: keys of no width hung the column search
    @Test
    fun boundsNotYetLaidOutLeaveTheKeysTheirWidth() {
        val ui = popup(arrayOf("a", "b", "c"), Rect(480, 500, 520, 550), Rect())
        assertEquals(keyWidth, ui.keyWidth)
    }

    @Test
    fun keysTooWideToFitAcrossAreNarrowerAndAllTheirLabelsSmallerAlike() {
        val long = arrayOf("example", "ex", "examples", "e", "exam")
        val roomy = popup(long, Rect(480, 500, 520, 550))
        // five across, each half as wide as the widest label and its padding (6 dp a side, at 1 dp = 1 px)
        val fitting = roomy.labels().maxOf { it.paint.measureText(it.text.toString()) }.toInt() + 12
        val narrow = Rect(0, 0, 5 * (fitting / 2), 600)
        val ui = popup(long, Rect(0, 500, 40, 550), narrow)
        assertEquals(fitting / 2, ui.keyWidth)
        val sizes = ui.labels().map { it.textSize }.toSet()
        assertEquals(1, sizes.size)
        assertTrue(sizes.single() < roomy.labels().first().textSize)
    }

    // KB-16: releasing on the bottom row of a two-row popup typed the key above it
    @Test
    fun theBottomRowOfTwoIsTypedWhereDrawnAndOnTheTriggerToo() {
        val ui = popup(10, Rect(480, 500, 520, 550))
        val cells = drawn(ui)
        val rows = ui.root.childCount
        assertEquals(2, rows)
        val bottom = cells.filterValues { it.first == 0 }
        assertTrue(bottom.isNotEmpty())
        bottom.forEach { (label, cell) ->
            // anywhere in the cell, its top edge to its bottom
            for (f in listOf(0.05f, 0.5f, 0.95f)) {
                ui.onChangeFocus((cell.second + 0.5f) * keyWidth, (rows - 1 + f) * keyHeight)
                assertEquals(label, KeyAction.FcitxKeyAction(label), ui.onTrigger())
            }
        }
        // the finger still on its key, below the popup: the bottom row
        val first = cells.entries.first { it.value.first == 0 }
        ui.onChangeFocus((first.value.second + 0.5f) * keyWidth, rows * keyHeight + 30f)
        assertEquals(KeyAction.FcitxKeyAction(first.key), ui.onTrigger())
    }
}
