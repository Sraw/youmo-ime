/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

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
        val newer = ScrollListStore.parse("@9\nnine\tt|?")
        val menu = ScrollListMenu("nine", 0, newer["nine"][0])
        assertNull(menu.change(newer, "nine", Op.Remove, readOnly = false))
        assertNull(menu.change(abc, "nine", Op.Edit, readOnly = true))
        assertNull(ScrollListMenu("nine", 1, Text("b")).change(abc, "nine", Op.Remove, readOnly = true))
    }

    @Test
    fun anItemNoLongerWhereItWasHasNoMenuAndNoChange() {
        val moved = ScrollListMenu("nine", 1, Text("a"))
        assertNull(moved.entries(abc, readOnly = false))
        assertNull(moved.change(abc, "nine", Op.Remove, readOnly = false))
        assertNull(ScrollListMenu("nine", 5, Text("a")).entries(abc, readOnly = false))
        // the column shows another list now (an address field's)
        assertNull(ScrollListMenu("nine", 1, Text("b")).change(abc, ScrollDefaults.URL, Op.Remove, readOnly = false))
        assertNull(ScrollListMenu("nine", 1, Text("b")).change(abc, null, Op.Remove, readOnly = false))
    }

    @Test
    fun aPickLeftOutChangesNothing() {
        assertNull(ScrollListMenu("nine", 0, Text("a")).change(abc, "nine", Op.ToFront, readOnly = false))
        val one = ScrollListStore.Empty.with("nine", listOf(Text("a")))
        assertNull(ScrollListMenu("nine", 0, Text("a")).change(one, "nine", Op.Remove, readOnly = false))
        assertNull(ScrollListMenu("nine", 1, Text("b")).change(abc, "nine", Op.Edit, readOnly = false))
    }

    @Test
    fun removingIsUndoneExactly() {
        val change = ScrollListMenu("nine", 1, Text("b")).change(abc, "nine", Op.Remove, readOnly = false)!!
        assertEquals(listOf(Text("a"), Command("copy")), change.store["nine"])
        assertEquals(abc, change.undo.undone(change.store, readOnly = false))
    }

    @Test
    fun movingToTheFrontIsUndoneExactly() {
        val change = ScrollListMenu("nine", 2, Command("copy")).change(abc, "nine", Op.ToFront, readOnly = false)!!
        assertEquals(listOf(Command("copy"), Text("a"), Text("b")), change.store["nine"])
        assertEquals(abc, change.undo.undone(change.store, readOnly = false))
    }

    @Test
    fun theDefaultsRemovedFromAreNoSettingOnceUndone() {
        val change = ScrollListMenu("nine", 0, ScrollDefaults.of("nine")!![0]).change(ScrollListStore.Empty, "nine", Op.Remove, readOnly = false)!!
        assertEquals(ScrollListStore.Empty, change.undo.undone(change.store, readOnly = false))
    }

    @Test
    fun aHiddenCommandKeepsItsPlaceOnceUndone() {
        val hidden = ScrollListStore.parse("@1\nnine\tt|a\tc|later_command\tt|b\tt|c")
        assertEquals(listOf(Text("a"), Text("b"), Text("c")), hidden["nine"])
        val change = ScrollListMenu("nine", 1, Text("b")).change(hidden, "nine", Op.Remove, readOnly = false)!!
        assertEquals(hidden, change.undo.undone(change.store, readOnly = false))
        assertEquals(listOf(Text("a"), Command("later_command"), Text("b"), Text("c")), hidden.stored("nine"))
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
