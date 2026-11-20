/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Whether a segment is typed or was the room: [VoiceGain] lifts a quiet room, and the recognizer reads its floor as
 * a short English phrase (「I'm sorry.」). Soft speech is no louder over its floor, so level alone cannot tell; what
 * was heard does: Chinese or longer is typed; a few Latin letters ([latinShort]) only when loud and voiced (silero).
 * Sound here is its own on purpose: under [SILENT] is no room, so a floor is a room's. Fed by the capture thread and
 * asked by the recognizer, both under one lock.
 */
class VoiceQuiet {

    // a window each: where it ends (samples from the opening), its level (NaN: no sound), voiced
    private var ends = LongArray(INITIAL)
    private var levels = DoubleArray(INITIAL)
    private var voiced = BooleanArray(INITIAL)

    // windows read with no detector (its model failed to load): what they are in is kept
    private var blind = BooleanArray(INITIAL)
    private var count = 0
    private var read = 0L
    private var withSound = 0

    // a 1 dB bin each from [BOTTOM] up: the floor without sorting
    private val bins = IntArray(BINS)
    private var binned = 0

    /** [speech] null: no VAD. */
    @Synchronized
    fun window(samples: FloatArray, n: Int = samples.size, speech: Boolean? = false) {
        if (n <= 0) return
        var sum = 0.0
        for (i in 0 until n) sum += samples[i].toDouble() * samples[i]
        read += n
        // NaN or infinite samples (a HAL's garbage) are no sound, not a crash; digital silence's sparse ±1 would set the floor far too low
        val audible = sum > 0.0 && !sum.isInfinite() && 10 * log10(sum / n) >= SILENT
        val sound = audible && ++withSound > SKIP
        val db = if (sound) 10 * log10(sum / n) else Double.NaN
        if (sound) {
            bins[(db - BOTTOM).roundToInt().coerceIn(0, BINS - 1)]++
            binned++
        }
        if (count == ends.size) grow()
        ends[count] = read
        levels[count] = db
        voiced[count] = speech == true
        blind[count] = speech == null
        count++
    }

    /** A short Latin phrase only when loud ([LOUD], or [RISE] over the floor) and voiced; kept where there was no VAD. */
    @Synchronized
    fun keeps(text: String, from: Long, to: Long): Boolean {
        if (!latinShort(text)) return true
        val first = firstEndingAfter(from)
        val last = minOf(firstEndingAfter(to - 1) + 1, count)
        var anyVoiced = false
        var anyBlind = false
        val top = DoubleArray(RANK) { Double.NEGATIVE_INFINITY }
        for (i in first until last) {
            if (voiced[i]) anyVoiced = true
            if (blind[i]) anyBlind = true
            // NaN (no sound) is above nothing
            if (levels[i] > top[RANK - 1]) insert(top, levels[i])
        }
        val level = top[RANK - 1]
        if (anyBlind) return true
        return anyVoiced && level.isFinite() && (level >= LOUD || level - floor() >= RISE)
    }

    // highest first, its last one going
    private fun insert(top: DoubleArray, l: Double) {
        var k = top.size - 1
        while (k > 0 && top[k - 1] < l) {
            top[k] = top[k - 1]
            k--
        }
        top[k] = l
    }

    private fun firstEndingAfter(sample: Long): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ends[mid] <= sample) lo = mid + 1 else hi = mid
        }
        return lo
    }

    // the [FLOOR_PERCENT]th percentile of the windows with sound
    private fun floor(): Double {
        val k = binned * FLOOR_PERCENT / 100
        var seen = 0
        for (b in 0 until BINS) {
            seen += bins[b]
            if (seen > k) return BOTTOM + b
        }
        return BOTTOM
    }

    private fun grow() {
        ends = ends.copyOf(ends.size * 2)
        levels = levels.copyOf(levels.size * 2)
        voiced = voiced.copyOf(voiced.size * 2)
        blind = blind.copyOf(blind.size * 2)
    }

    companion object {
        /** dBFS (the 3rd loudest window) loud whatever the floor: the gain lifts no more than 20 dB to -30. */
        const val LOUD = -50.0

        /** A steady noise's 3rd loudest window is 1 (white) to 5 (pink) over its floor. */
        const val RISE = 8.0
        const val RANK = 3
        const val SKIP = 3

        /** A phone's quietest room is some -70. */
        const val SILENT = -100.0
        const val FLOOR_PERCENT = 10

        /** 「I'm sorry」 is 7, 「Look friends」 11. */
        const val SHORT = 12

        private const val BOTTOM = -120.0
        private const val BINS = 121
        private const val INITIAL = 256

        /** What the recognizer makes of a room. */
        fun latinShort(text: String): Boolean {
            var letters = 0
            var i = 0
            while (i < text.length) {
                val c = text.codePointAt(i)
                if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) return false
                if (Character.isLetterOrDigit(c)) letters++
                i += Character.charCount(c)
            }
            return letters in 1..SHORT
        }
    }
}
