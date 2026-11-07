/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Whether a segment heard is typed, or was the room. [VoiceGain] lifts a quiet room by up to 20 dB,
 * and the recognizer reads the lifted floor (a fan, traffic, zeroes) as a short English phrase,
 * 「I'm sorry.」 nearly always (a OnePlus 6T at -66 dBFS typed 「I'm sory」, 2026-11-06; an emulator's
 * silent microphone 「H」 and 「I'm」). Soft speech is no louder than such a room over its floor, so
 * no level tells them apart without losing it (077374d11's rule dropped 9 in 10 at 6 dB over the
 * room); what was heard does: a segment with a Chinese character in it, or longer than a short
 * phrase, is typed as before; one of a few Latin letters only ([latinShort]) only where it was
 * loud (at least [LOUD], or over the floor) and voiced, silero's say on the gained samples
 * (specs/app.md, Quiet: the measurements; quiet_eval.py).
 *
 * Fed each window as read: its raw samples (before the gain) and whether the VAD heard speech in it.
 * What counts as sound here is its own on purpose, not [VoiceOnset]'s (the first sample not zero:
 * the microphone up) nor [VoiceGain]'s (the level it lifts by): a window under [SILENT] is no room,
 * though the onset and the gain count it, so a floor is a room's and not a quiet microphone's.
 * A segment is told by its samples from the microphone's opening. The capture thread feeds it and
 * the recognizer's asks, so both are under one lock.
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

    // the levels of the windows with sound, a 1 dB bin each from [BOTTOM] up: the floor without sorting
    private val bins = IntArray(BINS)
    private var binned = 0

    /** The next [n] raw samples of [samples], the VAD having heard speech in them or not ([speech]; null: no VAD). */
    @Synchronized
    fun window(samples: FloatArray, n: Int = samples.size, speech: Boolean? = false) {
        if (n <= 0) return
        var sum = 0.0
        for (i in 0 until n) sum += samples[i].toDouble() * samples[i]
        read += n
        // NaN or infinite samples (a HAL's garbage) are no sound, not a crash on the capture thread;
        // under [SILENT] digital silence as zeroes are (the UAT clip microphone's sparse ±1 between
        // clips, -111 dBFS, would set the floor 50 dB under a real room's)
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

    /**
     * Whether [text], the segment heard from sample [from] to [to] of the opening, is typed: not a
     * short Latin phrase ([latinShort]); or one loud (at least [LOUD], or [RISE] over the floor)
     * that the VAD heard as speech: a lone 「OK」「Hi」 said at a normal level is (quiet_eval.py),
     * and loud alone is a fan's or the traffic's. Kept too where there was no VAD to ask.
     */
    @Synchronized
    fun keeps(text: String, from: Long, to: Long): Boolean {
        if (!latinShort(text)) return true
        val first = firstEndingAfter(from)
        // through the window sample to - 1 is in
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

    // [l] into [top], highest first, its last one going
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
        /** dBFS (the 3rd loudest window) that is loud whatever the floor: the gain lifts no more than 20 dB to -30 */
        const val LOUD = -50.0

        /** dB over the floor that is loud: a steady noise's 3rd loudest window is 1 (white) to 5 (pink) over its floor */
        const val RISE = 8.0
        const val RANK = 3
        const val SKIP = 3

        /** dBFS (a window's raw level) under which it is digital silence, not a room: a phone's quietest room is some -70 */
        const val SILENT = -100.0
        const val FLOOR_PERCENT = 10

        /** letters at most of a phrase taken for the room's: 「I'm sorry」 is 7, 「Look friends」 11 */
        const val SHORT = 12

        private const val BOTTOM = -120.0
        private const val BINS = 121
        private const val INITIAL = 256

        /** No Chinese character, no more than [SHORT] letters or digits: what the recognizer makes of a room. */
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
