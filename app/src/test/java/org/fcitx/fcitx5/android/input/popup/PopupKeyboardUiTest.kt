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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

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

    private fun popup(count: Int, trigger: Rect): PopupKeyboardUi {
        val keys = Array(count) { ('a' + it).toString() }
        return PopupKeyboardUi(
            ctx = RuntimeEnvironment.getApplication(),
            theme = ThemePreset.PixelDark,
            outerBounds = outer,
            triggerBounds = trigger,
            radius = 4f,
            keyWidth = keyWidth,
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

    // the inverse of onChangeFocus's mapping, to the middle of a cell
    private fun PopupKeyboardUi.select(row: Int, column: Int, rows: Int): KeyAction? {
        onChangeFocus((column + 0.5f) * keyWidth, (rows - row + 0.2f) * keyHeight)
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
        for (count in 1..23) assertTypedWhereDrawn(count, centre)
    }

    @Test
    fun aPopupAtAnEdgeTypesTheKeyUnderTheFinger() {
        assertTypedWhereDrawn(13, Rect(0, 500, 40, 550))
        assertTypedWhereDrawn(13, Rect(960, 500, 1000, 550))
    }

    @Test
    fun theTopRowKeepsItsEmptySlots() {
        val root = popup(13, Rect(480, 500, 520, 550)).root
        for (r in 0 until root.childCount) {
            assertEquals("row $r has a slot per column", 5, (root.getChildAt(r) as ViewGroup).childCount)
        }
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
    }
}
