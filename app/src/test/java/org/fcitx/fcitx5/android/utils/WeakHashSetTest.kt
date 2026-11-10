/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WeakHashSetTest {

    // a weak set whose one key the GC cleared after its size was asked: size 1, nothing to go through
    private class ClearedAfterSize : AbstractSet<String>() {
        override val size = 1
        override fun iterator(): Iterator<String> = emptyList<String>().iterator()
    }

    @Test
    fun aKeyClearedBetweenSizeAndIterationIsNoThrow() {
        assertThrows(NoSuchElementException::class.java) { ClearedAfterSize().toList() }
        assertEquals(emptyList<String>(), ClearedAfterSize().snapshot())
    }

    @Test
    fun everyKeyHeldIsInTheSnapshot() {
        val held = listOf("a", "b", "c").map { String(it.toCharArray()) }
        val set = WeakHashSet<String>().apply { addAll(held) }
        assertEquals(held.toSet(), set.snapshot().toSet())
        assertEquals(3, set.snapshot().size)
    }
}
