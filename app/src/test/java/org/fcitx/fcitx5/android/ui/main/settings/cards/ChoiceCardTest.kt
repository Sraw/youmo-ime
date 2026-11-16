/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.cards

import android.view.ContextThemeWrapper
import android.view.View
import android.view.View.MeasureSpec
import android.widget.RadioButton
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CollectionInfoCompat
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import splitties.dimensions.dp

/** A keyboard's card on the 按键字符和快捷键 page (dev/design/061-settings-cards.md §2.3, §2.8, §4.1). */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChoiceCardTest {

    private val ctx = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_FcitxAppTheme)

    private fun card(name: String = "九键") = ChoiceCard(ctx, name, KeyboardGlyphView(ctx, listOf(3, 3, 3)))

    private fun ChoiceCard.node() = AccessibilityNodeInfoCompat.wrap(target.createAccessibilityNodeInfo())

    @Test
    @Uat("AX-21")
    fun `the body is one node read as a radio button named by the keyboard`() {
        val c = card().apply { selected = true }
        val node = c.node()
        assertEquals(RadioButton::class.java.name, node.className)
        assertEquals("九键", node.text.toString())
        assertTrue(node.isCheckable)
        assertTrue(node.isChecked)
        assertTrue(c.target.isClickable)
        // the name and the picture are not nodes of their own
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, c.nameText.importantForAccessibility)
        c.selected = false
        assertFalse(c.node().isChecked)
    }

    @Test
    fun `a tap picks it`() {
        var picked = 0
        val c = card().apply { onPick = { picked++ } }
        c.target.performClick()
        assertEquals(1, picked)
    }

    @Test
    fun `the badge counts the keys changed, none at 0 nor while the strip shows`() {
        val c = card()
        assertFalse(c.badgeView.isVisible)
        assertEquals(ctx.getString(R.string.shortcut_card_unselected), ViewCompat.getStateDescription(c.target).toString())
        c.edited = 3
        assertTrue(c.badgeView.isVisible)
        assertEquals("3", c.badgeView.text.toString())
        // said after the state, a stateDescription set taking the place of the checked state's own words
        c.selected = true
        assertEquals(
            ctx.getString(R.string.shortcut_card_edited, ctx.getString(R.string.shortcut_card_selected), 3),
            ViewCompat.getStateDescription(c.target).toString(),
        )
        c.showStrip = true
        assertFalse(c.badgeView.isVisible)
        assertTrue(c.strip.isVisible)
        c.showStrip = false
        assertTrue(c.badgeView.isVisible)
        c.edited = 0
        assertFalse(c.badgeView.isVisible)
    }

    @Test
    fun `the chosen card is filled and bordered`() {
        val c = card()
        assertEquals(ctx.dp(1), c.view.strokeWidth)
        c.selected = true
        assertEquals(ctx.dp(2), c.view.strokeWidth)
        assertTrue(c.view.isSelected)
    }

    @Test
    fun `in a grid it says its place in the row`() {
        val grid = CardGrid(ctx, 4, CollectionInfoCompat.SELECTION_MODE_SINGLE)
        val cards = (0 until 8).map { card("k$it").apply { selected = it == 5 } }
        cards.forEach { grid.addView(it.cell) }
        grid.measure(MeasureSpec.makeMeasureSpec(ctx.dp(361), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        grid.layout(0, 0, grid.measuredWidth, grid.measuredHeight)
        val item = cards[5].node().collectionItemInfo
        assertEquals(5, item.rowIndex)
        assertTrue(item.isSelected)
        // two rows of four, as high as one another
        assertEquals(cards[0].cell.height, cards[4].cell.height)
        assertEquals(cards[0].view.height, cards[0].cell.height)
        assertEquals(cards[0].cell.top, cards[3].cell.top)
        assertTrue(cards[4].cell.top > cards[0].cell.bottom)
    }

    // [v]'s bounds in [c]'s cell
    private fun ChoiceCard.rectOf(v: View) = android.graphics.Rect(0, 0, v.width, v.height).also { cell.offsetDescendantRectToMyCoords(v, it) }

    @Test
    fun `the badge hangs over the corner, touching neither the picture nor the name`() {
        // the narrowest card (76 dp) to the widest of a phone's row of four (89 dp), with a count of two digits
        listOf(76, 89).forEach { wide ->
            val c = ChoiceCard(ctx, "全键盘", KeyboardGlyphView(ctx, listOf(10, 9, 7))).apply { edited = 26 }
            val grid = CardGrid(ctx, 1).apply { addView(c.cell) }
            grid.measure(MeasureSpec.makeMeasureSpec(ctx.dp(wide), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            grid.layout(0, 0, grid.measuredWidth, grid.measuredHeight)
            val badge = c.rectOf(c.badgeView)
            assertTrue(c.badgeView.isVisible)
            // over the card's top edge and past its end, the cell unclipped
            assertTrue("$badge", badge.top < 0 && badge.bottom > 0)
            assertTrue("$badge", badge.right > c.cell.width)
            assertFalse(c.cell.clipChildren)
            // the picture's drawn part: the middle WIDTH_PART of its width
            val glyph = c.rectOf(c.glyphView)
            val side = ((1 - KeyboardGlyphView.WIDTH_PART) / 2 * glyph.width()).toInt()
            val drawn = android.graphics.Rect(glyph.left + side, glyph.top, glyph.right - side, glyph.bottom)
            assertFalse("$wide dp: badge $badge, picture $drawn", android.graphics.Rect.intersects(badge, drawn))
            assertFalse("$wide dp: badge $badge, name ${c.rectOf(c.nameText)}", android.graphics.Rect.intersects(badge, c.rectOf(c.nameText)))
        }
    }

    @Test
    fun `names of two lines make every card two lines high`() {
        val c = card()
        c.nameLines = 2
        assertEquals(2, c.nameText.minLines)
    }

    @Test
    fun `the picture is no node and draws a square a key`() {
        val glyph = KeyboardGlyphView(ctx, listOf(10, 9, 7))
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, glyph.importantForAccessibility)
        glyph.measure(MeasureSpec.makeMeasureSpec(ctx.dp(80), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ctx.dp(KeyboardGlyphView.HEIGHT_DP), MeasureSpec.EXACTLY))
        glyph.layout(0, 0, glyph.measuredWidth, glyph.measuredHeight)
        // drawn without a crash, bars too, and nothing for no rows
        val bmp = android.graphics.Bitmap.createBitmap(glyph.width, glyph.height, android.graphics.Bitmap.Config.ARGB_8888)
        glyph.draw(android.graphics.Canvas(bmp))
        KeyboardGlyphView(ctx, listOf(1, 1), bars = true).apply { layout(0, 0, glyph.width, glyph.height) }.draw(android.graphics.Canvas(bmp))
        KeyboardGlyphView(ctx, emptyList()).apply { layout(0, 0, glyph.width, glyph.height) }.draw(android.graphics.Canvas(bmp))
    }
}
