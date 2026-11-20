/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.cards

import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.View
import android.view.View.MeasureSpec
import android.widget.CheckBox
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CollectionInfoCompat
import com.google.android.material.checkbox.MaterialCheckBox
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import splitties.dimensions.dp

@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsCardsTest {

    private val ctx = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_FcitxAppTheme)
    private fun dp(v: Int) = ctx.dp(v)

    private fun View.layOut(width: Int) {
        measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        layout(0, 0, measuredWidth, measuredHeight)
    }

    // the strip's children are its own: their frame is their place in it
    private fun View.rect() = Rect(left, top, right, bottom)

    // the checkbox's drawable less its empty sides
    private fun CheckStrip.drawnBox(): Rect = box.rect().apply { inset(dp(CheckStrip.BOX_INSET_DP), 0) }

    private fun strip(text: String) = CheckStrip(ctx).apply { count.text = text }

    @Test
    fun `a card has the shared look`() {
        val card = settingsCard(ctx)
        assertEquals(dp(12).toFloat(), card.radius, 0.5f)
        assertEquals(0f, card.cardElevation)
        assertEquals(dp(1), card.strokeWidth)
    }

    @Test
    fun `the strip is one node read as a checkbox, its box only a picture`() {
        val strip = strip("3/9").apply { state = MaterialCheckBox.STATE_CHECKED }
        val node = AccessibilityNodeInfoCompat.wrap(strip.createAccessibilityNodeInfo())
        assertEquals(CheckBox::class.java.name, node.className)
        assertTrue(node.isCheckable)
        assertTrue(node.isChecked)
        assertTrue(strip.isClickable)
        assertTrue(strip.isFocusable)
        assertFalse(strip.box.isClickable)
        assertFalse(strip.box.isFocusable)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, strip.box.importantForAccessibility)
        // the margins taking back the empty sides are sized for Material's 32 dp drawable
        assertEquals(dp(CheckStrip.BOX_DP), strip.box.buttonDrawable!!.intrinsicWidth)
    }

    @Test
    fun `the strip's node is checked, partly checked or not as its box`() {
        val strip = strip("0/9")
        val partly = ctx.getString(R.string.settings_card_partial)
        listOf(
            MaterialCheckBox.STATE_CHECKED to AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE,
            MaterialCheckBox.STATE_INDETERMINATE to AccessibilityNodeInfoCompat.CHECKED_STATE_PARTIAL,
            MaterialCheckBox.STATE_UNCHECKED to AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE,
            MaterialCheckBox.STATE_INDETERMINATE to AccessibilityNodeInfoCompat.CHECKED_STATE_PARTIAL,
            MaterialCheckBox.STATE_CHECKED to AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE,
        ).forEach { (state, checked) ->
            strip.state = state
            assertEquals(state, strip.box.checkedState)
            val node = AccessibilityNodeInfoCompat.wrap(strip.createAccessibilityNodeInfo())
            assertEquals(checked, node.checked)
            // partly checked is said even where a reader can't tell the third state
            val said = if (state == MaterialCheckBox.STATE_INDETERMINATE) partly else null
            assertEquals(said, ViewCompat.getStateDescription(strip)?.toString())
        }
    }

    @Test
    fun `an owner's own saying of the state is kept`() {
        val strip = strip("3/9")
        ViewCompat.setStateDescription(strip, "3 of 9 chosen")
        strip.state = MaterialCheckBox.STATE_INDETERMINATE
        assertEquals("3 of 9 chosen", ViewCompat.getStateDescription(strip))
        strip.state = MaterialCheckBox.STATE_CHECKED
        assertEquals("3 of 9 chosen", ViewCompat.getStateDescription(strip))
    }

    @Test
    fun `a tap on the strip asks its owner and changes nothing itself`() {
        var asked = 0
        val strip = strip("0/9").apply {
            state = MaterialCheckBox.STATE_INDETERMINATE
            onCheck = { asked++ }
        }
        strip.performClick()
        assertEquals(1, asked)
        assertEquals(MaterialCheckBox.STATE_INDETERMINATE, strip.state)
    }

    @Test
    @Uat("WD-64")
    fun `an unusable strip is dimmed and inert`() {
        var asked = 0
        val strip = strip("0/9").apply { onCheck = { asked++ } }
        strip.setUsable(false)
        assertEquals(CheckStrip.DISABLED_ALPHA, strip.alpha)
        assertFalse(AccessibilityNodeInfoCompat.wrap(strip.createAccessibilityNodeInfo()).isEnabled)
        strip.performClick()
        assertEquals(0, asked)
        strip.setUsable(false, dim = 0.5f) // the rare page's switch off
        assertEquals(0.5f, strip.alpha)
        strip.setUsable(true)
        assertEquals(1f, strip.alpha)
        strip.performClick()
        assertEquals(1, asked)
    }

    // a 76 dp card's strip still fits "26/26" on one line beside the box
    @Test
    fun `the strip on the narrowest card keeps its count whole beside the box`() {
        val strip = strip("26/26")
        strip.layOut(dp(76))
        assertTrue(strip.height >= dp(48))
        assertEquals(1, strip.count.lineCount)
        assertWhole(strip.count)
        assertFalse(Rect.intersects(strip.drawnBox(), strip.count.rect()))
        assertTrue(strip.drawnBox().width() <= dp(20))
        assertTrue(strip.drawnBox().left >= strip.paddingLeft) // the box keeps the strip's edge
        assertEquals(dp(4), strip.paddingBottom)
    }

    // on a 360 dp phone a full group's strip wraps and grows rather than cut it
    @Test
    fun `a count too long for one line wraps and the strip grows`() {
        val text = "已屏蔽 14,802/14,802 已屏蔽 14,802/14,802"
        val strip = CheckStrip(ctx, edgeDp = 12).apply { count.text = text }
        strip.layOut(dp(76))
        assertTrue(strip.count.lineCount > 1)
        assertWhole(strip.count)
        assertTrue(strip.height >= strip.count.height + strip.paddingTop)
        assertTrue(strip.height > dp(48))
        assertFalse(Rect.intersects(strip.drawnBox(), strip.count.rect()))
    }

    private fun assertWhole(text: TextView) {
        val layout = text.layout
        for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
        assertEquals(text.text.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue(layout.height <= text.height - text.paddingTop - text.paddingBottom)
    }

    private fun grid(n: Int, columns: Int = 4) = CardGrid(ctx, columns, CollectionInfoCompat.SELECTION_MODE_SINGLE).let { grid ->
        grid to (0 until n).map { i -> TextView(ctx).apply { text = if (i == 2) "a\nb\nc" else "x" }.also { grid.addView(it) } }
    }

    @Test
    fun `the grid lays cards in rows, each row's cards as high as its highest`() {
        val (grid, cards) = grid(8)
        grid.layOut(dp(361))
        assertEquals(2, grid.rows)
        val cell = (dp(361) - 3 * dp(8)) / 4
        cards.forEach { assertEquals(cell, it.width) }
        assertTrue(cards.take(4).all { it.height == cards[2].height })
        assertTrue(cards[2].height > cards[4].height)
        assertTrue(cards.drop(4).all { it.height == cards[4].height })
        assertEquals(cards[0].bottom + dp(8), cards[4].top)
        assertEquals(cards[0].right + dp(8), cards[1].left)
        assertEquals(cards[4].bottom, grid.height)
        assertEquals(1 to 2, grid.placeOf(cards[6]))
        assertNull(grid.placeOf(TextView(ctx)))

        grid.columns = 3
        assertTrue(grid.isLayoutRequested)
        grid.layOut(dp(361))
        assertEquals(3, grid.rows)
        assertEquals(cards[0].bottom + dp(8), cards[3].top)
        assertEquals(1 to 0, grid.placeOf(cards[3]))
    }

    // read as a list, "item 2 of 8", whatever the columns
    @Test
    fun `the grid reads as a list of its cards`() {
        val (grid, cards) = grid(8)
        cards[6].isSelected = true
        grid.layOut(dp(361))
        val info = AccessibilityNodeInfoCompat.wrap(grid.createAccessibilityNodeInfo()).collectionInfo
        assertEquals(8, info.rowCount)
        assertEquals(1, info.columnCount)
        assertEquals(CollectionInfoCompat.SELECTION_MODE_SINGLE, info.selectionMode)
        val item = grid.itemOf(cards[6])!!
        assertEquals(6, item.rowIndex)
        assertEquals(0, item.columnIndex)
        assertTrue(item.isSelected)
        assertFalse(grid.itemOf(cards[1])!!.isSelected)
        assertNull(grid.itemOf(TextView(ctx)))
    }

    @Test
    fun `a gone card takes no cell and is not counted, an invisible one keeps its cell`() {
        val (grid, cards) = grid(9)
        cards[1].visibility = View.GONE
        grid.layOut(dp(361))
        assertEquals(2, grid.rows)
        assertEquals(0, cards[1].measuredWidth)
        assertNull(grid.placeOf(cards[1]))
        assertNull(grid.itemOf(cards[1]))
        assertEquals(0 to 1, grid.placeOf(cards[2]))
        assertEquals(cards[0].right + dp(8), cards[2].left)
        assertEquals(1, grid.itemOf(cards[2])!!.rowIndex)
        assertEquals(cards[0].bottom + dp(8), cards[5].top) // 0, 2, 3, 4 the first row; 5..8 the second
        assertEquals(cards[5].top, cards[8].top)
        assertEquals(8, AccessibilityNodeInfoCompat.wrap(grid.createAccessibilityNodeInfo()).collectionInfo.rowCount)

        // an INVISIBLE card keeps its cell but is not read
        cards[3].visibility = View.INVISIBLE
        grid.layOut(dp(361))
        assertEquals(0 to 2, grid.placeOf(cards[3]))
        assertEquals(cards[2].right + dp(8), cards[3].left)
        assertNull(grid.itemOf(cards[3]))
        assertEquals(2, grid.itemOf(cards[4])!!.rowIndex)
        assertEquals(7, AccessibilityNodeInfoCompat.wrap(grid.createAccessibilityNodeInfo()).collectionInfo.rowCount)
    }

    @Test
    fun `right to left, the first card is on the right`() {
        val (grid, cards) = grid(8)
        grid.layoutDirection = View.LAYOUT_DIRECTION_RTL
        grid.layOut(dp(361))
        assertEquals(grid.width, cards[0].right)
        assertEquals(cards[1].right + dp(8), cards[0].left)
        assertTrue(cards[3].left in 0 until 4) // the fourth at the left edge, less what the columns can't share
        assertEquals(cards[0].right, cards[4].right)
    }
}
