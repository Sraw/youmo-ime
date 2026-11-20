/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.cards

import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
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

/** A group card: a body to look in, a strip to check, read as two. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class GroupCardTest {

    private val ctx = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_FcitxAppTheme)

    private fun card() = GroupCard(ctx).apply { bind("三级字", "通用规范字表的三级", listOf("犇", "堃"), "三级字，1,605 个，已屏蔽 0", "查看这一组的字") }

    private fun node(v: View) = AccessibilityNodeInfo.obtain().also { v.onInitializeAccessibilityNodeInfo(it) }

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    @Test
    fun `a tap on the body opens, on the strip checks, never the other`() {
        val card = card()
        var opened = 0
        var checked = 0
        card.onOpen = { opened++ }
        card.strip.onCheck = { checked++ }
        card.body.performClick()
        assertEquals(1 to 0, opened to checked)
        card.strip.performClick()
        assertEquals(1 to 1, opened to checked)
    }

    @Test
    fun `the body is a button saying the group, a tap read as looking in`() {
        val card = card()
        val info = node(card.body)
        assertEquals(Button::class.java.name, info.className)
        assertEquals("三级字，1,605 个，已屏蔽 0", card.body.contentDescription.toString())
        assertEquals("查看这一组的字", info.actionList.single { it.id == AccessibilityNodeInfo.ACTION_CLICK }.label)
        assertEquals(card.samples.text.toString(), "犇 堃")
    }

    @Test
    fun `what is in the body is not read apart`() {
        val card = card()
        val inside = all(card.body).drop(1)
        assertTrue(inside.isNotEmpty())
        assertTrue(inside.all { it.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO })
    }

    @Test
    @Uat("AX-21")
    fun `a card read body then strip, the next card after this strip`() {
        val first = card()
        val second = card()
        second.readAfter(first)
        assertEquals(first.body.id, first.strip.accessibilityTraversalAfter)
        assertEquals(first.strip.id, second.body.accessibilityTraversalAfter)
        assertTrue(first.body.id != View.NO_ID && first.strip.id != View.NO_ID)
    }

    @Test
    fun `in a grid the body is its card's place in the list`() {
        val grid = CardGrid(ctx, 2)
        val cards = List(4) { card().also { grid.addView(it.view) } }
        assertEquals(3, node(cards[3].body).collectionItemInfo.rowIndex)
        cards[1].view.visibility = View.GONE
        assertEquals("a card gone is not counted", 2, node(cards[3].body).collectionItemInfo.rowIndex)
    }

    @Test
    fun `no samples, no row`() {
        val card = GroupCard(ctx).apply { bind("扩展区", "扩展 B 以后", emptyList(), "扩展区", "查看") }
        assertEquals(View.GONE, card.samples.visibility)
        card.bind("扩展区", "扩展 B 以后", listOf("𠮷"), "扩展区", "查看")
        assertEquals(View.VISIBLE, card.samples.visibility)
    }

    @Test
    @Uat("WD-64")
    fun `unusable, only the strip fades and stops, the body still opens`() {
        val card = card()
        var opened = false
        var checked = false
        card.onOpen = { opened = true }
        card.strip.onCheck = { checked = true }
        card.strip.setUsable(false, 0.5f)
        card.strip.performClick()
        card.body.performClick()
        assertFalse(checked)
        assertTrue(opened)
        assertEquals(0.5f, card.strip.alpha)
        assertEquals(1f, card.body.alpha)
        assertEquals(1f, card.view.alpha)
    }
}
