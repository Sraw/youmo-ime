/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Lifts quiet speech before the VAD and recognizer, in place: VOICE_RECOGNITION has no AGC, and soft speech at -50 to
 * -60 dBFS never fires silero. The level is the [RANK]rd loudest of the last [SPAN] windows, so a click does not hold
 * the gain down; the first [SKIP] sounding windows (start-up pop) are left out; it falls at most [DECAY_DB_S]; it only
 * lifts, never past [PEAK]. Matches voice-eval's `Gain2` sample for sample from the first sounding window. One per opening.
 */
class VoiceGain(private val sampleRate: Int) {

    init {
        require(sampleRate > 0) { "sampleRate $sampleRate" }
    }

    private var seen = 0
    // a ring: no boxing or sorting a window on the capture thread
    private val recent = DoubleArray(SPAN)
    private var recentCount = 0
    private var recentNext = 0
    private var env: Double? = null

    /** As a factor. */
    var gain = 1.0
        private set

    /** dBFS; null before there is one: the debug log's. */
    var envPeak: Double? = null
        private set

    /** The debug log's; not the highest gain, which digital silence before a clip drives to the maximum. */
    var peakGain = 1.0
        private set

    fun apply(samples: FloatArray) {
        val count = samples.size
        if (count == 0) return
        var atPeak = false
        var peak = 0f
        for (s in samples) peak = max(peak, abs(s))
        // counted from the first sound: a HAL warming up hands back zeroes, which used up the skip and left the pop lifted
        if (seen > 0 || peak > 0f) seen++
        if (seen > SKIP) {
            recent[recentNext] = db(samples)
            recentNext = (recentNext + 1) % SPAN
            if (recentCount < SPAN) recentCount++
            if (recentCount >= RANK) {
                val level = third()
                val last = env
                val now = if (last == null) level else max(level, last - DECAY_DB_S * count / sampleRate)
                env = now
                if (now >= (envPeak ?: now)) {
                    envPeak = now
                    atPeak = true
                }
            }
        }
        val e = env
        var g = if (e == null) 1.0 else 10.0.pow(min(MAX_DB, max(0.0, TARGET_DB - e)) / 20)
        if (peak > 0f) g = max(1.0, min(g, PEAK / peak))
        if (g > gain) {
            // rising: across the window, not a step the recognizer hears as a click
            val from = gain
            val step = if (count > 1) (g - from) / (count - 1) else 0.0
            for (i in 0 until count) {
                val r = if (i == count - 1) g else from + step * i
                samples[i] = (samples[i] * r.toFloat()).coerceIn(-1f, 1f)
            }
        } else {
            val r = g.toFloat()
            for (i in 0 until count) samples[i] = (samples[i] * r).coerceIn(-1f, 1f)
        }
        gain = g
        if (atPeak) peakGain = g
    }

    // as sorting them would have it, ties counted apart
    private fun third(): Double {
        var a = Double.NEGATIVE_INFINITY
        var b = Double.NEGATIVE_INFINITY
        var c = Double.NEGATIVE_INFINITY
        for (i in 0 until recentCount) {
            val v = recent[i]
            if (v > a) {
                c = b
                b = a
                a = v
            } else if (v > b) {
                c = b
                b = v
            } else if (v > c) {
                c = v
            }
        }
        return c
    }

    private fun db(samples: FloatArray): Double {
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return 10 * log10(sum / samples.size + SILENCE)
    }

    companion object {
        const val TARGET_DB = -30.0
        const val MAX_DB = 20.0
        const val DECAY_DB_S = 3.0
        const val SPAN = 31
        const val RANK = 3
        const val SKIP = 3
        const val PEAK = 0.99
        private const val SILENCE = 1e-12
    }
}
