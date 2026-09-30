/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyNeighboursTest {

    private fun of(c: Char) = KeyNeighbours.of(c).toSortedSet().joinToString("")

    @Test
    fun aKeyTouchesThoseBesideItAboveAndBelow() {
        assertEquals("aeqs", of('w'))
        assertEquals("qsw", of('a'))
        assertEquals("iklp", of('o'))
        // the bottom row sits a whole key in: each key there under one middle key alone
        assertEquals("bjm", of('n'))
        assertEquals("sx", of('z'))
        assertEquals("", of('1'))
        assertEquals("", of('A'))
    }

    @Test
    fun touchingIsMutual() {
        for (c in 'a'..'z') for (d in KeyNeighbours.of(c)) assertTrue("$c $d", c in KeyNeighbours.of(d))
    }
}
