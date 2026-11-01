/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SeenWordsTest {

    @Test
    fun aPassSaysNewAsASetWouldAndAClearForgetsIt() {
        val seen = SeenWords()
        val random = Random(15)
        // passes small and large (the table grows inside one), ids near, far, repeated and negative
        for (pass in 0 until 200) {
            seen.clear()
            val set = HashSet<Int>()
            val n = if (pass % 10 == 0) 5_000 else random.nextInt(1, 80)
            repeat(n) {
                val id = when (random.nextInt(4)) {
                    0 -> random.nextInt(-3, 40)
                    1 -> random.nextInt(0, 400_000)
                    2 -> random.nextInt()
                    else -> pass * 64 + random.nextInt(64)
                }
                assertEquals("pass $pass id $id", set.add(id), seen.add(id))
            }
        }
    }

    @Test
    fun aWordSeenBeforeAClearIsNewAfterIt() {
        val seen = SeenWords()
        assertTrue(seen.add(7))
        assertFalse(seen.add(7))
        seen.clear()
        assertTrue(seen.add(7))
        // grown past its first size, then cleared: none of the moved words is still seen
        repeat(1000) { assertTrue(seen.add(it + 100)) }
        seen.clear()
        repeat(1000) { assertTrue(seen.add(it + 100)) }
        assertFalse(seen.add(7 + 100))
    }
}
