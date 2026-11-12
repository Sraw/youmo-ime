/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.scene.rules.KeyMarks.At
import org.fcitx.fcitx5.android.scene.rules.KeyMarks.Placed
import org.fcitx.fcitx5.android.scene.rules.KeyMarks.Slot
import org.fcitx.fcitx5.android.scene.rules.KeyMarks.Spots
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyMarksTest {

    private val bools = listOf(false, true)

    private fun place(symbolAt: At, shortcutAt: At, landscape: Boolean = false, compact: Boolean = false) =
        KeyMarks.place("，", "全选", symbolAt, shortcutAt, landscape, compact)

    @Test
    fun eachWhereItsSettingSays() {
        // the defaults: 标点位置 右上, 快捷键图标位置 左上
        assertEquals(Placed.of(Slot.TopLeft to "全选", Slot.TopRight to "，"), place(At.SymbolDefault, At.ShortcutDefault))
        assertEquals(Placed.of(Slot.TopRight to "全选", Slot.BottomLeft to "，"), place(At.BottomLeft, At.TopRight))
        assertEquals(Placed.of(Slot.Bottom to "全选", Slot.TopLeft to "，"), place(At.TopLeft, At.Bottom))
        assertEquals(Placed.of(Slot.BottomLeft to "全选", Slot.Below to "，"), place(At.Bottom, At.BottomLeft))
        // 无: that one not drawn, the other where it was
        assertEquals(Placed.of(Slot.TopLeft to "全选"), place(At.None, At.TopLeft))
        assertEquals(Placed.of(Slot.TopRight to "，"), place(At.TopRight, At.None))
        assertEquals(Placed<String>(), place(At.None, At.None))
        // either alone on a key where it would be
        assertEquals(Placed.of(Slot.BottomRight to "，"), KeyMarks.place("，", null, At.BottomRight, At.TopLeft, false))
        assertEquals(Placed.of(Slot.TopLeft to "全选"), KeyMarks.place(null, "全选", At.BottomRight, At.TopLeft, false))
    }

    @Test
    fun onLowKeysTheSymbolsBottomTopRightTheShortcutsTheFirstCornerFree() {
        for (squeezed in listOf(true to false, false to true)) {
            val (landscape, compact) = squeezed
            // the symbol's: top right, bottom right where the shortcuts are
            assertEquals(Placed.of(Slot.TopLeft to "全选", Slot.TopRight to "，"), place(At.Bottom, At.TopLeft, landscape, compact))
            assertEquals(Placed.of(Slot.TopRight to "全选", Slot.BottomRight to "，"), place(At.Bottom, At.TopRight, landscape, compact))
            assertEquals(Placed.of(Slot.BottomLeft to "全选", Slot.TopRight to "，"), place(At.Bottom, At.BottomLeft, landscape, compact))
            assertEquals(Placed.of(Slot.BottomRight to "全选", Slot.TopRight to "，"), place(At.Bottom, At.BottomRight, landscape, compact))
            assertEquals(Placed.of(Slot.TopRight to "，"), place(At.Bottom, At.None, landscape, compact))
            // the shortcut's as the symbol's: top right, bottom right, top left, bottom left, the first the symbol's is not
            assertEquals(Placed.of(Slot.BottomRight to "全选", Slot.TopRight to "，"), place(At.TopRight, At.Bottom, landscape, compact))
            assertEquals(Placed.of(Slot.TopRight to "全选", Slot.TopLeft to "，"), place(At.TopLeft, At.Bottom, landscape, compact))
            assertEquals(Placed.of(Slot.TopRight to "全选", Slot.BottomLeft to "，"), place(At.BottomLeft, At.Bottom, landscape, compact))
            assertEquals(Placed.of(Slot.TopRight to "全选"), place(At.None, At.Bottom, landscape, compact))
            assertEquals(Spots(At.TopRight, At.BottomRight), KeyMarks.onKeys(At.Bottom, At.Bottom, landscape, compact))
        }
        // the other places as in portrait
        assertEquals(Placed.of(Slot.TopRight to "全选", Slot.BottomLeft to "，"), place(At.BottomLeft, At.TopRight, compact = true))
        assertEquals(Spots(At.Bottom, At.TopLeft), KeyMarks.onKeys(At.Bottom, At.TopLeft, false, false))
    }

    @Test
    fun onePlaceInBothTheSymbolYields() {
        // as an import or a backup can write them; the settings page offers neither
        assertEquals(Spots(At.BottomRight, At.TopRight), KeyMarks.settled(At.TopRight, At.TopRight))
        assertEquals(Spots(At.TopRight, At.BottomRight), KeyMarks.settled(At.BottomRight, At.BottomRight))
        assertEquals(Spots(At.BottomLeft, At.TopLeft), KeyMarks.settled(At.TopLeft, At.TopLeft))
        assertEquals(Spots(At.TopLeft, At.BottomLeft), KeyMarks.settled(At.BottomLeft, At.BottomLeft))
        assertEquals(Spots(At.TopRight, At.Bottom), KeyMarks.settled(At.Bottom, At.Bottom))
        assertEquals("both 无 is allowed", Spots(At.None, At.None), KeyMarks.settled(At.None, At.None))
        for (a in At.entries) for (b in At.entries) if (a != b) assertEquals(Spots(a, b), KeyMarks.settled(a, b))
        // placed as read
        assertEquals(Placed.of(Slot.TopRight to "全选", Slot.BottomRight to "，"), place(At.TopRight, At.TopRight))
        assertEquals(Placed.of(Slot.Bottom to "全选", Slot.TopRight to "，"), place(At.Bottom, At.Bottom))
    }

    private data class Case(
        val symbolAt: At, val shortcutAt: At, val landscape: Boolean, val compact: Boolean, val symbol: String?, val shortcut: String?,
    )

    // every combination, those the settings page does not offer too: the shortcut in its setting's
    // slot, the symbol in its own, never two in one place or the bottom edge's middle under the letter
    @Test
    fun everyCombinationOneMarkASlot() {
        val cases = At.entries.flatMap { symbolAt -> At.entries.map { symbolAt to it } }
            .flatMap { (symbolAt, shortcutAt) -> bools.flatMap { landscape -> bools.map { Case(symbolAt, shortcutAt, landscape, it, null, null) } } }
            .flatMap { c -> listOf("，", null).flatMap { symbol -> listOf("全选", null).map { c.copy(symbol = symbol, shortcut = it) } } }
        cases.forEach(::check)
        assertEquals(6 * 6 * 2 * 2 * 2 * 2, cases.distinct().size)
    }

    private fun check(c: Case) {
        val placed = KeyMarks.place(c.symbol, c.shortcut, c.symbolAt, c.shortcutAt, c.landscape, c.compact)
        val low = c.landscape || c.compact
        val shortcutSlot = KeyMarks.shortcutSlot(KeyMarks.onKeys(c.symbolAt, c.shortcutAt, c.landscape, c.compact).shortcut)
        if (!low) assertEquals("$c", KeyMarks.shortcutSlot(c.shortcutAt), shortcutSlot)
        assertTrue("$c: no bottom edge on low keys", !low || shortcutSlot != Slot.Bottom)
        assertEquals("$c", if (c.shortcut != null) shortcutSlot else null, placed.slotOf("全选"))
        val s = placed.slotOf("，")
        val alone = KeyMarks.symbolSlot(c.symbolAt, c.shortcutAt, c.landscape, c.compact)
        assertEquals("$c: one place whatever the key has", if (c.symbol != null) alone else null, s)
        if (alone == null) return assertTrue("$c", c.symbolAt == At.None)
        assertNotEquals("$c", shortcutSlot, alone)
        assertTrue("$c", !(alone == Slot.Below && shortcutSlot == Slot.Bottom))
        assertTrue("$c: under the letter only with room", alone != Slot.Below || !low)
        // the legal ones (apart) where chosen
        if (c.symbolAt != c.shortcutAt && c.symbolAt != At.Bottom) assertEquals("$c", c.symbolAt.corner, alone)
    }

    @Test
    fun aFunctionKeysMarksEachAtItsOwnWaysSetting() {
        fun slot(at: At) = if (at == At.Bottom) Slot.Bottom else at.corner
        for (upAt in At.entries) for (downAt in At.entries) {
            if (upAt == downAt && upAt != At.None) continue // the settings page keeps them apart
            val both = KeyMarks.placeFunction("清除", "恢复", true, true, upAt, downAt, false, false)
            val want = listOfNotNull(slot(upAt)?.let { it to "清除" }, slot(downAt)?.let { it to "恢复" })
            assertEquals("$upAt $downAt", Placed.of(*want.toTypedArray()), both)
            assertEquals("$upAt $downAt: up off", Placed.of(*listOfNotNull(slot(downAt)?.let { it to "恢复" }).toTypedArray()),
                KeyMarks.placeFunction("清除", "恢复", false, true, upAt, downAt, false, false))
            assertEquals("$upAt $downAt: no down", Placed.of(*listOfNotNull(slot(upAt)?.let { it to "清除" }).toTypedArray()),
                KeyMarks.placeFunction("清除", null, true, true, upAt, downAt, false, false))
            // low keys: a 底部 to the first corner the other way's is not, the two never in one slot
            for (landscape in listOf(true, false)) {
                val low = KeyMarks.placeFunction("清除", "恢复", true, true, upAt, downAt, landscape, !landscape)
                if (upAt != At.Bottom) assertEquals("$upAt $downAt low", slot(upAt), low.slotOf("清除"))
                if (downAt != At.Bottom) assertEquals("$upAt $downAt low", slot(downAt), low.slotOf("恢复"))
                if (upAt == At.Bottom) assertEquals(KeyMarks.LowOrder.first { it != downAt }.corner, low.slotOf("清除"))
                if (downAt == At.Bottom) assertEquals(KeyMarks.LowOrder.first { it != upAt }.corner, low.slotOf("恢复"))
            }
        }
        // both 底部 on a low key: settled first (上滑 to 右上), then 下滑's 底部 the first corner left, 右下
        assertEquals(Placed.of(Slot.TopRight to "清除", Slot.BottomRight to "恢复"),
            KeyMarks.placeFunction("清除", "恢复", true, true, At.Bottom, At.Bottom, true, false))
        assertEquals(Placed.of(Slot.TopRight to "清除", Slot.BottomRight to "恢复"),
            KeyMarks.placeFunction("清除", "恢复", true, true, At.Bottom, At.Bottom, false, true))
        // one place in both (an import the settings page never wrote): read as settled, the up one yields
        assertEquals(Placed.of(Slot.TopRight to "清除", Slot.BottomRight to "恢复"),
            KeyMarks.placeFunction("清除", "恢复", true, true, At.BottomRight, At.BottomRight, false, false))
        assertEquals(Placed<String>(), KeyMarks.placeFunction(null, null, true, true, At.TopRight, At.BottomRight, false, false))
    }

    @Test
    fun slotsMirrorTopForBottom() {
        for (s in Slot.entries) {
            assertEquals(s.left, s.mirrored.left)
            assertEquals(s.right, s.mirrored.right)
            if (s != Slot.Below) assertEquals(s, s.mirrored.mirrored)
        }
        assertTrue(Slot.Bottom.centred && Slot.Top.centred && !Slot.TopLeft.centred)
        for (a in At.entries) if (a != At.None) assertNotEquals(a, a.yielded)
    }
}
