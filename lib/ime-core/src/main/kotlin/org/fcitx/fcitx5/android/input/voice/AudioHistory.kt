/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * The last [capacity] samples heard, by their place since the microphone opened. The VAD decides
 * speech has started a little after it has, and cuts the first consonant off ("开饭" came back
 * "派饭"): what came just before a stretch it cut is put back from here.
 */
class AudioHistory(private val capacity: Int) {

    private val ring = FloatArray(capacity)

    /** samples heard so far */
    var heard = 0L
        private set

    fun add(samples: FloatArray, count: Int = samples.size) {
        for (i in 0 until count) {
            ring[((heard + i) % capacity).toInt()] = samples[i]
        }
        heard += count
    }

    /** Up to [count] samples ending just before [start], as many as are still held. */
    fun before(start: Long, count: Int): FloatArray {
        val end = minOf(start, heard)
        val from = maxOf(end - count, heard - capacity, 0L)
        if (from >= end) return FloatArray(0)
        return FloatArray((end - from).toInt()) { ring[((from + it) % capacity).toInt()] }
    }
}
