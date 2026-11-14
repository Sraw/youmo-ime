/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class CausesTest {

    // audit-061 app-rest-13: the chain was cut at a count (MAX_CAUSES: 8 in PackStore, 16 in EngineBridge), and a cause
    // deeper than that (a PackUnavailableException under the engine's wrappers) was not found, the pack not read
    // through. 21 deep on purpose: past both of the old counts; a shorter chain would pass with a cut again
    @Test
    fun aChainIsFollowedToItsEnd() {
        val root = IOException("root")
        val top = (1..20).fold<Int, Throwable>(root) { cause, i -> RuntimeException("$i", cause) }
        val all = top.causes().toList()
        assertEquals(21, all.size)
        assertEquals(root, all.last())
    }

    @Test
    fun aChainThatLoopsBackEndsAtTheLoop() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertEquals(listOf(a, b), a.causes().toList())
    }
}
