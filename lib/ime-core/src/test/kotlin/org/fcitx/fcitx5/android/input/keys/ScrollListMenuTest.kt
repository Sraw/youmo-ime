/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.keys.ScrollListMenu.Entry
import org.fcitx.fcitx5.android.input.keys.ScrollListMenu.Op
import org.fcitx.fcitx5.android.scene.keys.ScrollDefaults
import org.fcitx.fcitx5.android.scene.keys.ScrollItem.Command
import org.fcitx.fcitx5.android.scene.keys.ScrollItem.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollListMenuTest {
    private val abc = ScrollListStore.Empty.with("nine", listOf(Text("a"), Text("b"), Command("copy")))

    private fun ops(menu: ScrollListMenu, store: ScrollListStore = abc) = menu.entries(store, readOnly = false)?.map { it.op }

    @Test
    fun theFirstHasNoToFrontTheOnlyOneNoRemove() {
        assertEquals(listOf(Op.ToFront, Op.Edit, Op.Remove), ops(ScrollListMenu("nine", 1, Text("b"))))
        assertEquals(listOf(Op.Edit, Op.Remove), ops(ScrollListMenu("nine", 0, Text("a"))))
        val one = ScrollListStore.Empty.with("nine", listOf(Text("a")))
        assertEquals(listOf(Op.Edit), ops(ScrollListMenu("nine", 0, Text("a")), one))
    }

    @Test
    fun readOnlyShowsAllThreeFaded() {
        val faded = Op.entries.map { Entry(it, enabled = false) }
        assertEquals(faded, ScrollListMenu("nine", 0, Text("a")).entries(abc, readOnly = true))
    }

    @Test
    fun anItemNoLongerWhereItWasHasNoMenu() {
        assertNull(ScrollListMenu("nine", 1, Text("a")).entries(abc, readOnly = false))
        assertNull(ScrollListMenu("nine", 5, Text("a")).entries(abc, readOnly = false))
    }

    @Test
    fun aChangeDoesNothingWhen() {
        val b = ScrollListMenu("nine", 1, Text("b"))
        val one = ScrollListStore.Empty.with("nine", listOf(Text("a")))
        assertRows(
            "a newer version's store" to {
                val newer = ScrollListStore.parse("@9\nnine\tt|?")
                assertNull(ScrollListMenu("nine", 0, newer["nine"][0]).change(newer, "nine", Op.Remove, readOnly = false))
            },
            "read-only, each op" to { assertEquals(emptyList<Op>(), Op.entries.filter { b.change(abc, "nine", it, readOnly = true) != null }) },
            "the item moved" to { assertNull(ScrollListMenu("nine", 1, Text("a")).change(abc, "nine", Op.Remove, readOnly = false)) },
            "the index out of range" to { assertNull(ScrollListMenu("nine", 5, Text("a")).change(abc, "nine", Op.Remove, readOnly = false)) },
            "another list shown (an address field's)" to { assertNull(b.change(abc, ScrollDefaults.URL, Op.Remove, readOnly = false)) },
            "no list shown" to { assertNull(b.change(abc, null, Op.Remove, readOnly = false)) },
            "the first's ToFront, left out" to { assertNull(ScrollListMenu("nine", 0, Text("a")).change(abc, "nine", Op.ToFront, readOnly = false)) },
            "the only one's Remove, left out" to { assertNull(ScrollListMenu("nine", 0, Text("a")).change(one, "nine", Op.Remove, readOnly = false)) },
            "Edit, which opens the settings instead" to { assertNull(b.change(abc, "nine", Op.Edit, readOnly = false)) },
        )
    }

    @Test
    fun aChangeIsUndoneExactly() = assertRows(
        "removing b" to {
            val change = ScrollListMenu("nine", 1, Text("b")).change(abc, "nine", Op.Remove, readOnly = false)!!
            assertEquals(listOf(Text("a"), Command("copy")), change.store["nine"])
            assertEquals(abc, change.undo.undone(change.store, readOnly = false))
        },
        "moving a command to the front" to {
            val change = ScrollListMenu("nine", 2, Command("copy")).change(abc, "nine", Op.ToFront, readOnly = false)!!
            assertEquals(listOf(Command("copy"), Text("a"), Text("b")), change.store["nine"])
            assertEquals(abc, change.undo.undone(change.store, readOnly = false))
        },
    )

    @Test
    fun theDefaultsRemovedFromAreNoSettingOnceUndone() {
        val change = ScrollListMenu("nine", 0, ScrollDefaults.of("nine")!![0]).change(ScrollListStore.Empty, "nine", Op.Remove, readOnly = false)!!
        assertEquals(ScrollListStore.Empty, change.undo.undone(change.store, readOnly = false))
    }

    @Test
    fun aHiddenCommandKeepsItsPlaceOnceUndone() {
        val hidden = ScrollListStore.parse("@1\nnine\tt|a\tc|later_command\tt|b\tt|c")
        assertEquals(listOf(Text("a"), Text("b"), Text("c")), hidden["nine"])
        assertEquals(listOf(Text("a"), Command("later_command"), Text("b"), Text("c")), hidden.stored("nine"))
        val change = ScrollListMenu("nine", 1, Text("b")).change(hidden, "nine", Op.Remove, readOnly = false)!!
        assertEquals(hidden, change.undo.undone(change.store, readOnly = false))
    }

    @Test
    fun aListChangedSinceIsNotUndone() {
        val change = ScrollListMenu("nine", 1, Text("b")).change(abc, "nine", Op.Remove, readOnly = false)!!
        val later = change.store.with("nine", listOf(Text("a"), Command("copy"), Text("z")))
        assertNull(change.undo.undone(later, readOnly = false))
        assertNull(change.undo.undone(change.store, readOnly = true))
        assertNull(change.undo.undone(ScrollListStore.parse("@9"), readOnly = false))
        // another list changed meanwhile is left as it is
        val other = change.store.with(ScrollDefaults.URL, listOf(Text(".cn")))
        assertEquals(abc.with(ScrollDefaults.URL, listOf(Text(".cn"))), change.undo.undone(other, readOnly = false))
    }
}
