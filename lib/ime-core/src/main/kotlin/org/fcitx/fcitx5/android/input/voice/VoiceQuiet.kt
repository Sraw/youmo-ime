/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Whether a talk heard speech at all, or only the room. [VoiceGain] lifts a quiet room by up to 20
 * dB, and the recognizer reads a lifted noise floor as a short phrase (a quiet room at -66 dBFS
 * typed 「I'm sory」 on a OnePlus 6T, 2026-11-06): what it heard is then not typed.
 *
 * Fed the raw windows, before the gain. Speech is a sound [LOUD] or louder, which the gain lifts
 * little, or one that rises [RISE] above the talk's own floor: soft speech in a quiet room stands
 * out of it, the room's steady noise does not. The level is the [RANK]rd loudest window (a click of
 * a window or two is not speech), the floor the [FLOOR_PERCENT]th percentile; windows all zero (a
 * HAL warming up, digital silence) and the first [SKIP] with sound (the microphone's start-up pop)
 * are left out. Zeroes alone are no speech either: an emulator's silent microphone typed 「H」 and
 * 「I'm」 (2026-11-07).
 */
class VoiceQuiet {

    // windows by level, a 1 dB bin each from [BOTTOM] dBFS up: no list kept on the capture thread
    private val bins = IntArray(BINS)
    private var count = 0
    private var withSound = 0

    /** Whether what was heard is to be typed: true once a window has made it speech, for the rest of the talk. */
    var speech = false
        private set

    /** The next [n] raw samples of [samples]. */
    fun window(samples: FloatArray, n: Int = samples.size) {
        if (n <= 0) return
        var sum = 0.0
        for (i in 0 until n) sum += samples[i].toDouble() * samples[i]
        if (sum == 0.0) return
        if (++withSound <= SKIP) return
        val db = 10 * log10(sum / n)
        bins[(db - BOTTOM).roundToInt().coerceIn(0, BINS - 1)]++
        count++
        if (!speech && count >= RANK) {
            val level = fromTop(RANK - 1)
            speech = level >= LOUD || level - floor() >= RISE
        }
    }

    // the level of the k-th (from 0) loudest window
    private fun fromTop(k: Int): Double {
        var seen = 0
        for (b in BINS - 1 downTo 0) {
            seen += bins[b]
            if (seen > k) return BOTTOM + b.toDouble()
        }
        return BOTTOM
    }

    private fun floor(): Double {
        val k = count * FLOOR_PERCENT / 100
        var seen = 0
        for (b in 0 until BINS) {
            seen += bins[b]
            if (seen > k) return BOTTOM + b.toDouble()
        }
        return BOTTOM
    }

    companion object {
        /** dBFS of a window that is speech whatever the floor: the gain lifts it 20 dB at most to -30 */
        const val LOUD = -50.0

        /** dB above the floor that is speech: a steady noise's loudest windows are some 1 (white) to 5 (pink) above its floor */
        const val RISE = 8.0
        const val RANK = 3
        const val SKIP = 3
        const val FLOOR_PERCENT = 10
        private const val BOTTOM = -120.0
        private const val BINS = 121
    }
}
