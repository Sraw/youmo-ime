/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Collections

class ParallelTest {

    @Test
    fun resultsComeInTheItemsOrderWhateverTheThreads() {
        val items = (0 until 10).toList()
        for (threads in listOf(1, 3, 4, 10, 50)) {
            assertEquals("$threads", items.map { it * it }, dealt(items, threads, { Unit }) { hand -> hand.map { it * it } })
        }
        assertEquals(emptyList<Int>(), dealt(emptyList<Int>(), 4, { Unit }) { hand -> hand })
    }

    @Test
    fun eachThreadHasItsOwnWorker() {
        val workers = Collections.synchronizedList(ArrayList<StringBuilder>())
        val hands = dealt((0 until 7).toList(), 3, { StringBuilder().also { workers += it } }) { hand ->
            hand.map { append(it); toString() }
        }
        assertEquals(3, workers.size)
        // dealt a card at a time: the first thread has 0, 3 and 6
        assertEquals(listOf("0", "1", "2", "03", "14", "25", "036"), hands)
    }

    @Test
    fun aWorkersFailureIsThrownAsItself() {
        assertThrows(IllegalStateException::class.java) {
            dealt((0 until 4).toList(), 2, { Unit }) { hand -> hand.map { check(it != 3); it } }
        }
        assertThrows(IllegalStateException::class.java) {
            dealt((0 until 4).toList(), 2, { Unit }) { hand -> hand.drop(1) }
        }
    }
}
