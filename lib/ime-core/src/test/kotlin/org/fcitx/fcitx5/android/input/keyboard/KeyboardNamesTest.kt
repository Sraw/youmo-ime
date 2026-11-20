/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardNamesTest {

    @Test
    fun aKeyboardOfALayoutOfTheUsersIsNamedByItsBaseAndIdAtStamp() {
        assertEquals("Number.PHONE|my-num@5-9", KeyboardNames.of("Number.PHONE", CustomChoice("my-num", "My", "number", null, stamp = "5-9")))
        assertEquals("Number", KeyboardNames.of("Number", null))
        assertEquals("Number.PHONE", KeyboardNames.base("Number.PHONE|my-num@5-9"))
        assertEquals("my-num@5-9", KeyboardNames.custom("Number.PHONE|my-num@5-9"))
        assertNull(KeyboardNames.custom("Number.PHONE"))
    }

    // the pads one number board is for keep each other; a file replaced drops the older stamp's
    @Test
    fun onlyAnotherStampOfTheSameLayoutIsDropped() {
        assertFalse(KeyboardNames.stale("Number|my-num@5-9", "my-num@5-9"))
        assertFalse(KeyboardNames.stale("Number.PHONE|my-num@5-9", "my-num@5-9"))
        assertTrue(KeyboardNames.stale("Number|my-num@4-9", "my-num@5-9"))
        assertTrue(KeyboardNames.stale("Number.PHONE|my-num@4-9", "my-num@5-9"))
        assertFalse(KeyboardNames.stale("Number|other@4-9", "my-num@5-9"))
        assertFalse(KeyboardNames.stale("Number", "my-num@5-9"))
    }
}
