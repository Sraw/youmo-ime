/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import com.k2fsa.sherpa.onnx.SpeechSegment
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric: VoiceEngine holds a main-thread Handler
@RunWith(RobolectricTestRunner::class)
class VoiceEngineStretchTest {

    @Test
    fun theLeadInIsReadFromWhereTheVadStartsInTheRecord() {
        // the record's samples numbered: sample i is i
        val history = VoiceEngine.history().apply { add(FloatArray(SAMPLES) { it.toFloat() }) }
        val samples = floatArrayOf(-1f, -2f)
        // a VAD that came in after a backlog dropped 1000 samples: its 6000 is the record's 7000
        val got = VoiceEngine.stretch(SpeechSegment(6000, samples), history, dropped = 1000)
        val leadIn = FloatArray(LEAD_IN) { (7000 - LEAD_IN + it).toFloat() }
        assertArrayEquals(leadIn + samples, got, 0f)
    }

    private companion object {
        const val SAMPLES = 16000
        // VoiceEngine's: 0.3 s at 16 kHz
        const val LEAD_IN = 4800
    }
}
