/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Test

/** What an edited list stores as its first: `*` only where `*` resolves to the same item as the resolver reads it. */
class KeyEditFirstTest {
    @Test
    fun theFirstStoredIsStarForTheListsFirstItemBangForNone() = assertRows(
        "the first item: *" to {
            assertEquals(First.Default, KeyEdit.firstToStore("x", listOf("x", "y")))
            assertEquals(First.Default, KeyEdit.firstToStore("A", listOf("A", "B", "2")))
        },
        "another item: itself (九键 2's default first, the digit, is :2)" to {
            assertEquals(First.Literal("y"), KeyEdit.firstToStore("y", listOf("x", "y")))
            assertEquals(First.Literal("2"), KeyEdit.firstToStore("2", listOf("A", "B", "C", "2", "a")))
        },
        "none: ! (a 注音 group's default)" to {
            assertEquals(First.None, KeyEdit.firstToStore(null, listOf("ㄅ", "ㄉ")))
            assertEquals(First.None, KeyEdit.firstToStore(null, emptyList()))
        },
    )
}
