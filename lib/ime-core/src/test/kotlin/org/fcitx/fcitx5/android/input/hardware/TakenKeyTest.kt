/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakenKeyTest {

    private val key = TakenKey()
    private var asked = 0

    private fun down(code: Int, repeat: Boolean = false, take: Boolean = true) = key.down(code, repeat) {
        asked++
        take
    }

    @Test
    fun aTakenPressHasItsReleaseTakenToo() {
        assertTrue(down(DEL))
        assertTrue(key.up(DEL))
        // once: the next release is the key's own
        assertFalse(key.up(DEL))
    }

    @Test
    fun heldDownItActsOnceAndItsRepeatsGoNowhere() {
        assertTrue(down(DEL))
        assertTrue(down(DEL, repeat = true))
        assertTrue(down(DEL, repeat = true))
        assertEquals(1, asked)
        assertTrue(key.up(DEL))
    }

    @Test
    fun aPressNotTakenLeavesTheKeyAlone() {
        assertFalse(down(DEL, take = false))
        assertFalse(down(DEL, repeat = true))
        assertFalse(key.up(DEL))
    }

    @Test
    fun aRepeatNeverTakesAKeyWhosePressWentOn() {
        assertFalse(down(DEL, repeat = true))
        assertEquals(0, asked)
    }

    @Test
    fun anotherKeyPressedKeepsTheOneTakenUntilItsRelease() {
        assertTrue(down(DEL))
        assertFalse(down(OTHER, take = false))
        assertTrue(down(DEL, repeat = true))
        assertFalse(key.up(OTHER))
        assertTrue(key.up(DEL))
        assertFalse(key.up(DEL))
    }

    // one key taken at a time: another taken meanwhile is the one, the first's repeats and release go on
    @Test
    fun anotherKeyTakenMeanwhileIsTheOneTaken() {
        assertTrue(down(DEL))
        assertTrue(down(OTHER))
        assertFalse(down(DEL, repeat = true))
        assertFalse(key.up(DEL))
        assertTrue(key.up(OTHER))
    }

    private companion object {
        const val DEL = 112
        const val OTHER = 29
    }
}
