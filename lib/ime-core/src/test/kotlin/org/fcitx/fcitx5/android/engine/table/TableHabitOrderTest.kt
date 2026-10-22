/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Habits
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [TableHabitOrder]'s edges, directly (review R11 F2997); the orders it makes are TableHabitsTest's. */
class TableHabitOrderTest {

    private val texts = listOf("叫", "员", "叫员", "员叫")
    private fun text(at: Int) = texts[at]

    @Test
    fun thePinnedIsMovedFirstTheRestInOrder() {
        assertArrayEquals(intArrayOf(2, 0, 1, 3), TableHabitOrder.pinnedFirst(intArrayOf(0, 1, 2, 3), "叫员", ::text))
        assertArrayEquals(intArrayOf(3, 1, 0, 2), TableHabitOrder.pinnedFirst(intArrayOf(1, 0, 3, 2), "员叫", ::text))
    }

    @Test
    fun noPinFirstAlreadyOrNotThereIsTheOrderAsItWas() {
        val order = intArrayOf(0, 1, 2)
        assertArrayEquals(order, TableHabitOrder.pinnedFirst(order, null, ::text))
        assertArrayEquals(order, TableHabitOrder.pinnedFirst(order, "叫", ::text))
        assertArrayEquals(order, TableHabitOrder.pinnedFirst(order, "员叫", ::text))
    }

    @Test
    fun nothingIsOrderedWhereNothingIsLearned() {
        assertNull(TableHabitOrder.order(Habits.OFF, true, Code(Kinds.table("wubi"), "kkkk"), intArrayOf(0, 1), 2, { true }, ::text))
    }
}
