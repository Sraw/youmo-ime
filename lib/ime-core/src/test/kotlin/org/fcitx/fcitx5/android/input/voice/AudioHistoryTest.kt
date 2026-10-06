/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioHistoryTest {

    private fun samples(from: Int, until: Int) = FloatArray(until - from) { (from + it).toFloat() }

    @Test
    fun whatCameJustBeforeIsGivenBack() {
        val history = AudioHistory(10)
        history.add(samples(0, 4))
        history.add(samples(4, 8))
        assertEquals(8L, history.heard)
        assertArrayEquals(samples(3, 6), history.before(6, 3), 0f)
    }

    @Test
    fun onlyWhatIsStillHeldAndNothingBeforeTheStart() {
        val history = AudioHistory(10)
        history.add(samples(0, 25))
        // 15 to 24 held: of 10..19, 15..19
        assertArrayEquals(samples(15, 20), history.before(20, 10), 0f)
        assertArrayEquals(samples(0, 2), AudioHistory(10).apply { add(samples(0, 5)) }.before(2, 10), 0f)
        assertEquals(0, history.before(12, 3).size)
    }

    @Test
    fun aStartNotYetHeardEndsAtWhatWas() {
        val history = AudioHistory(10)
        history.add(samples(0, 6), count = 4)
        assertArrayEquals(samples(2, 4), history.before(9, 2), 0f)
    }
}
