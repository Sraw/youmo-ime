/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.Uat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceOnsetTest {

    @Test
    @Uat("VO-26")
    fun theFirstSampleNotZeroIsTheOnsetOnce() {
        val onset = VoiceOnset()
        // zeroes are not the microphone on
        assertFalse(onset.window(FloatArray(512)))
        assertFalse(onset.window(FloatArray(512)))
        assertNull(onset.before)
        val w = FloatArray(512).also { it[100] = -1e-4f }
        assertTrue(onset.window(w))
        assertEquals(1124L, onset.before)
        // told once: the next windows are not the onset again
        assertFalse(onset.window(FloatArray(512) { 0.5f }))
        assertEquals(1124L, onset.before)
    }

    @Test
    @Uat("VO-27")
    fun twoSecondsOfZeroesIsASilentMicrophoneToldOnce() {
        val onset = VoiceOnset(silentAfter = 1024)
        onset.window(FloatArray(512))
        assertFalse(onset.silent())
        onset.window(FloatArray(512))
        assertTrue(onset.silent())
        onset.window(FloatArray(512))
        assertFalse(onset.silent())
        // sound after it: the onset, as ever
        assertTrue(onset.window(FloatArray(512).also { it[3] = 0.1f }))
        assertEquals(1539L, onset.before)
    }

    @Test
    fun twoSecondsAtAnyRate() {
        // 48 kHz: 96 000 zeroes; 187 windows are 95 744, 188 are 96 256
        val onset = VoiceOnset(48_000)
        repeat(187) { onset.window(FloatArray(512)) }
        assertFalse(onset.silent())
        onset.window(FloatArray(512))
        assertTrue(onset.silent())
    }

    @Test
    fun soundBeforeTheTwoSecondsIsNeverSilent() {
        val onset = VoiceOnset(silentAfter = 1024)
        assertTrue(onset.window(FloatArray(512).also { it[0] = 0.1f }))
        onset.window(FloatArray(4096))
        assertFalse(onset.silent())
    }

    @Test
    fun onlyTheSamplesReadCount() {
        val onset = VoiceOnset()
        // a short read: what is past n in the buffer is last read's, not this one's
        val w = FloatArray(512).also { it[300] = 0.2f }
        assertFalse(onset.window(w, 256))
        assertTrue(onset.window(FloatArray(512).also { it[0] = 0.1f }, 1))
        assertEquals(256L, onset.before)
    }
}
