/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.keys

import org.fcitx.fcitx5.android.scene.keys.SlotDefaults.Slot
import kotlin.test.Test
import kotlin.test.assertEquals

class SlotDefaultsTest {
    @Test
    fun aCharacterKeysFirstIsOnTheOrientationsSide() {
        assertEquals(Slot.First, SlotDefaults.of(KeyId("full/c"), up = true, firstUp = true))
        assertEquals(Slot.Command("copy"), SlotDefaults.of(KeyId("full/c"), up = false, firstUp = true))
        assertEquals(Slot.Command("copy"), SlotDefaults.of(KeyId("full/c"), up = true, firstUp = false))
        assertEquals(Slot.None, SlotDefaults.of(KeyId("full/q"), up = false, firstUp = true))
    }

    @Test
    fun aGroupTakesItsMostAskedCommand() {
        assertEquals("paste", SlotDefaults.command(KeyId("g18/cv")))
        assertEquals("select_all", SlotDefaults.command(KeyId("g14/as")))
        // 2 is abc: copy before select all
        assertEquals("copy", SlotDefaults.command(KeyId("t9/2")))
        assertEquals(null, SlotDefaults.command(KeyId("zy9/1")))
    }

    @Test
    fun functionKeysHaveTheirCommandsBothWays() {
        assertEquals(Slot.Command("edit_menu"), SlotDefaults.of(KeyId("fn/space"), up = true, firstUp = false))
        assertEquals(Slot.Command("restore"), SlotDefaults.of(KeyId("fn/backspace"), up = false, firstUp = true))
        assertEquals(Slot.None, SlotDefaults.of(KeyId("fn/comma"), up = true, firstUp = true))
    }

    @Test
    fun iconsAreTheScenesNames() {
        assertEquals("content-paste", SlotDefaults.icon("paste"))
        assertEquals("cursor-move", SlotDefaults.icon("edit_menu"))
        assertEquals("undo", SlotDefaults.icon("undo"))
    }
}
