/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * When the microphone is really on: the first sample that is not zero. Until then a HAL warming up
 * hands back zeroes (or nothing), and what is said meanwhile is lost; so 正在听 shows only from
 * here, 准备中 before (the user's rule, dev/design/voice-start-latency.md). Fed a window at a time,
 * the raw samples, before any gain.
 */
class VoiceOnset(
    sampleRate: Int = 16_000,
    /** zero samples read, with none other yet, after which the microphone is told as silent: 2 s */
    private val silentAfter: Long = 2L * sampleRate,
) {

    /** samples read before the first one not zero; null until it comes: the debug log's */
    var before: Long? = null
        private set

    private var read = 0L
    private var toldSilent = false

    /**
     * True once, when [silentAfter] samples are in and all were zero (the privacy toggle off, the
     * capture taken by a call): 麦克风没有声音 instead of 准备中 (the user's rule). The
     * first sample not zero after it is still the onset: 正在听 then.
     */
    fun silent(): Boolean {
        if (toldSilent || before != null || read < silentAfter) return false
        toldSilent = true
        return true
    }

    /** The next [n] samples of [samples]: true for the window the first sample not zero is in, once. */
    fun window(samples: FloatArray, n: Int = samples.size): Boolean {
        if (before != null) return false
        for (i in 0 until n) {
            if (samples[i] != 0f) {
                before = read + i
                return true
            }
        }
        read += n
        return false
    }
}
