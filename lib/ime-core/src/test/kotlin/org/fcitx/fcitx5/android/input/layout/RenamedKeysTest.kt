/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.layout

import org.fcitx.fcitx5.android.input.keys.First
import org.fcitx.fcitx5.android.input.keys.ItemMark
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.keys.UserList
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// a theme pack's keys renamed as its layout is made the user's (061-custom-layout §2.3, M3): the settings copied, the old kept
class RenamedKeysTest {
    private val renames = mapOf("fn/mine" to "t1234abcd-k18/r1c1", "g18/nope" to "t1234abcd-k18/r1c3")

    @Test
    fun aListIsCopiedWholeItsMarksWithItAndTheOldKept() {
        val marked = UserList(false, listOf("\$year", "a"), First.Literal("a"), mapOf("\$year" to ItemMark.Variables))
        val text = KeyLists.of(mapOf(KeyId("fn/mine") to marked, KeyId("g18/q") to UserList.Off)).serialize()
        val out = KeyLists.parse(RenamedKeys.keyLists(text, renames))!!
        assertEquals(marked, out[KeyId("t1234abcd-k18/r1c1")])
        assertEquals(marked, out[KeyId("fn/mine")])
        assertEquals(UserList.Off, out[KeyId("g18/q")])
        // nothing of g18/nope set: nothing under its new name
        assertNull(out[KeyId("t1234abcd-k18/r1c3")])
        assertEquals(3, out.lists.size)
    }

    @Test
    fun aListTheNewNameHasIsTheUsersAndKept() {
        val mine = UserList(false, listOf("x"))
        val text = KeyLists.of(mapOf(KeyId("fn/mine") to UserList.Off, KeyId("t1234abcd-k18/r1c1") to mine)).serialize()
        assertNull(RenamedKeys.keyLists(text, renames))
    }

    @Test
    fun listsNotReadAsTheyAreAreLeft() {
        assertNull(RenamedKeys.keyLists(null, renames))
        assertNull(RenamedKeys.keyLists("", renames))
        // @1's, migrated elsewhere first; a later version's
        assertNull(RenamedKeys.keyLists("@1\nfn/mine\toff", renames))
        assertNull(RenamedKeys.keyLists("@9\nfn/mine\toff", renames))
        // nothing renamed set: unchanged
        assertNull(RenamedKeys.keyLists(KeyLists.of(mapOf(KeyId("g18/q") to UserList.Off)).serialize(), renames))
    }

    @Test
    fun swipesAreCopiedEachWayApart() {
        val up = SwipeSlot.Text("hi")
        val text = SwipeSlots.Default.with("fn/mine", true, up).with("g18/nope", false, SwipeSlot.Empty).serialize()
        val out = SwipeSlots.parse(RenamedKeys.keySwipes(text, renames)!!)
        assertEquals(up, out.resolve("t1234abcd-k18/r1c1", true))
        assertEquals(false, out.isSet("t1234abcd-k18/r1c1", false))
        assertEquals(SwipeSlot.Empty, out.resolve("t1234abcd-k18/r1c3", false))
        assertEquals(up, out.resolve("fn/mine", true))
        // one the new name has: the user's
        val own = SwipeSlots.Default.with("fn/mine", true, up).with("t1234abcd-k18/r1c1", true, SwipeSlot.Text("mine"))
        assertNull(RenamedKeys.keySwipes(own.serialize(), renames))
        // none set, or none: nothing
        assertNull(RenamedKeys.keySwipes("", renames))
        assertNull(RenamedKeys.keySwipes(null, renames))
    }
}
