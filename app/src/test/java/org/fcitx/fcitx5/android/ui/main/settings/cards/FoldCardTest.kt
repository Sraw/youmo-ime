/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.cards

import android.app.Activity
import android.view.ContextThemeWrapper
import android.view.View
import android.view.View.MeasureSpec
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.ui.cards.Accordion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import splitties.dimensions.dp

/** The key editor's folding cards and the layout holding them (dev/design/061-settings-cards.md §5.1). */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FoldCardTest {

    private val ctx = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_FcitxAppTheme)
    private fun dp(v: Int) = ctx.dp(v)

    // a card holding [high] dp of content, a field at its top
    private fun card(title: String, high: Int, info: String? = null) = FoldCard(ctx, title, info).apply {
        content.addView(EditText(ctx), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        content.addView(View(ctx), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(high - 48)))
    }

    private fun three() = listOf(card("长按菜单", 300, "hint"), card("上滑", 200), card("下滑", 200))

    // laid out [room] dp high at most, as the dialog's content
    private fun AccordionLayout.layOut(room: Int) {
        measure(MeasureSpec.makeMeasureSpec(dp(360), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(room), MeasureSpec.AT_MOST))
        layout(0, 0, measuredWidth, measuredHeight)
    }

    private fun FoldCard.node() = AccessibilityNodeInfoCompat.wrap(header.createAccessibilityNodeInfo())

    @Test
    @Uat("AX-21")
    fun `the title row is one button saying the summary and whether it is open`() {
        val c = card("下滑", 100)
        c.summary("命令：全选")
        val node = c.node()
        assertEquals(Button::class.java.name, node.className)
        assertEquals("下滑", node.text.toString())
        assertEquals(ctx.getString(R.string.swipe_ways_said, "下滑", "命令：全选"), c.header.contentDescription.toString())
        assertEquals(ctx.getString(R.string.settings_card_collapsed), ViewCompat.getStateDescription(c.header).toString())
        val click = node.actionList.first { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        assertEquals(ctx.getString(R.string.settings_card_expand), click.label.toString())
        c.expanded = true
        assertEquals(ctx.getString(R.string.settings_card_expanded), ViewCompat.getStateDescription(c.header).toString())
        assertEquals(ctx.getString(R.string.settings_card_collapse), c.node().actionList.first { it.id == AccessibilityNodeInfo.ACTION_CLICK }.label.toString())
        assertEquals("命令：全选", c.summaryShown.toString())
        // no ⓘ without a hint
        assertNull(c.infoButton)
    }

    @Test
    fun `the info is a node of its own, said 说明`() {
        val c = card("长按菜单", 100, "hint")
        val info = c.infoButton!!
        assertEquals(ctx.getString(R.string.settings_card_info), info.contentDescription.toString())
        assertTrue(info.isClickable)
        assertSame(c.header, info.parent)
    }

    @Test
    fun `one card open at a time, the first when the titles stay put`() {
        val cards = three()
        val a = AccordionLayout(ctx, cards)
        a.layOut(700)
        assertTrue(a.fixedTitles)
        assertEquals(0, a.open)
        assertTrue(cards[0].expanded)
        cards[2].header.performClick()
        assertEquals(2, a.open)
        assertFalse(cards[0].expanded)
        assertTrue(cards[2].expanded)
        // its own title again: none open
        cards[2].header.performClick()
        assertNull(a.open)
        assertTrue(cards.none { it.expanded })
        a.showError(1)
        assertEquals(1, a.open)
    }

    @Test
    fun `with room short the open card scrolls inside itself, the titles in view`() {
        val cards = three()
        val a = AccordionLayout(ctx, cards)
        // 3 titles of 48 and 2 gaps of 8: 160; 420 left 260 for the open card, whose 300 scroll in it
        a.layOut(420)
        assertTrue(a.fixedTitles)
        assertEquals(0, a.open)
        assertTrue(cards[0].contentHeight < dp(300))
        assertTrue(a.measuredHeight <= dp(420))
        // every title inside the room
        cards.forEach { assertTrue(it.view.bottom <= dp(420)) }
    }

    @Test
    fun `with too little room the whole scrolls, all folded at the start, one tree as ever`() {
        val cards = three()
        val a = AccordionLayout(ctx, cards)
        val field = cards[0].content.getChildAt(1) as EditText
        val parent = field.parent
        a.layOut(250)
        assertFalse(a.fixedTitles)
        assertNull(a.open)
        assertTrue(cards.none { it.expanded })
        // folded in that very measure: three titles high, not the open card's height left empty (a phone on its side)
        assertTrue(a.measuredHeight < dp(200))
        assertTrue(cards[0].view.height < dp(100))
        // opened now: uncapped, the whole scrolling
        cards[0].header.performClick()
        a.layOut(250)
        assertFalse(a.fixedTitles)
        assertEquals(dp(300) + dp(12), cards[0].contentHeight)
        // the room grows (the keyboard put away): the card stays open, capped or not, its field where it was
        a.layOut(700)
        assertEquals(0, a.open)
        assertTrue(a.fixedTitles)
        assertSame(parent, field.parent)
        a.layOut(250)
        assertEquals(0, a.open)
        assertSame(parent, field.parent)
    }

    @Test
    @Uat("ST-153")
    fun `a card folded over its focused field lets the focus and the keyboard go`() {
        val cards = three()
        val a = AccordionLayout(ctx, cards)
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        host.setContentView(a)
        a.layOut(700)
        val field = cards[0].content.getChildAt(1) as EditText
        field.requestFocus()
        val imm = host.getSystemService(InputMethodManager::class.java)
        imm.showSoftInput(field, 0)
        assertTrue(field.hasFocus())
        assertTrue(shadowOf(imm).isSoftInputVisible)
        cards[1].header.performClick()
        // the field's content GONE clears its focus anyway; the keyboard staying up over it was the bug
        assertFalse(field.hasFocus())
        assertFalse(shadowOf(imm).isSoftInputVisible)
    }

    @Test
    fun `the least room grows with the font`() {
        val big = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_FcitxAppTheme).apply {
            applyOverrideConfiguration(android.content.res.Configuration(ctx.resources.configuration).apply { fontScale = 2f })
        }
        val cards = listOf(FoldCard(big, "a").apply {
            content.addView(View(big), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(400)))
        }, FoldCard(big, "b"))
        val a = AccordionLayout(big, cards)
        // titles ~104 dp: 300 leaves ~196, under 160 × 2
        a.measure(MeasureSpec.makeMeasureSpec(dp(360), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(300), MeasureSpec.AT_MOST))
        assertFalse(a.fixedTitles)
        assertEquals(Accordion.MIN_CONTENT_DP, 160)
    }

    @Test
    fun `the info opens its card and shows its words at the top, again hides them`() {
        val cards = three()
        val a = AccordionLayout(ctx, cards)
        a.layOut(700)
        cards[1].header.performClick()
        assertFalse(cards[0].expanded)
        cards[0].infoButton!!.performClick()
        assertEquals(0, a.open)
        assertTrue(cards[0].infoShown)
        val words = cards[0].content.getChildAt(0)
        assertTrue(words.isVisible)
        cards[0].infoButton!!.performClick()
        assertFalse(cards[0].infoShown)
        assertEquals(0, a.open)
        // folded, the words go with it
        cards[0].infoButton!!.performClick()
        cards[0].header.performClick()
        assertFalse(cards[0].infoShown)
        assertNotNull(words)
    }
}
