/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyllablesTest {

    @Test
    fun everyIdMapsBackToItsSpelling() {
        val all = (0 until Syllables.count).map { Syllables.spelling(it) }
        assertEquals("duplicates", all.size, all.toSet().size)
        all.forEachIndexed { id, s -> assertEquals(id, Syllables.id(s)) }
    }

    @Test
    fun theIdsAreFrozen() {
        // Compiled data stores these ids. If this fails, the list was reordered or trimmed:
        // only appending is allowed, and appending leaves these values as they are.
        assertEquals(0, Syllables.id("a"))
        assertEquals(Syllables.id("zuo") + 1, Syllables.id("A"))
        assertEquals(FROZEN_CHECKSUM, Syllables.checksum(FROZEN_COUNT))
        assertTrue(Syllables.count >= FROZEN_COUNT)
    }

    @Test
    fun dataFromBeforeAnAppendStillMatches() {
        assertTrue(Syllables.matches(FROZEN_COUNT, FROZEN_CHECKSUM))
        assertTrue(Syllables.matches(Syllables.count, Syllables.checksum()))
        assertTrue(Syllables.matches(400, Syllables.checksum(400)))
        assertFalse(Syllables.matches(400, Syllables.checksum(401)))
        assertFalse(Syllables.matches(Syllables.count + 1, Syllables.checksum()))
        assertFalse(Syllables.matches(0, Syllables.checksum(0)))
    }

    @Test
    fun nonSyllablesAreRejected() {
        assertEquals(-1, Syllables.id("ni hao"))
        assertEquals(-1, Syllables.id(""))
        assertEquals(-1, Syllables.id("lü"))
        assertEquals(-1, Syllables.id("Ni"))
    }

    private companion object {
        const val FROZEN_COUNT = 448
        const val FROZEN_CHECKSUM = 2064975150L
    }
}
