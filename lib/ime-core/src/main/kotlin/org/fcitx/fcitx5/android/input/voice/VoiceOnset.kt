/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/** The microphone is really on at the first nonzero sample (a warming HAL hands back zeroes): 正在听 from here, 准备中 before. Raw samples, before any gain. */
class VoiceOnset(
    sampleRate: Int = 16_000,
    /** All zeroes this long: the microphone is silent. */
    private val silentAfter: Long = 2L * sampleRate,
) {

    /** Null until it comes: the debug log's. */
    var before: Long? = null
        private set

    private var read = 0L
    private var toldSilent = false

    /** True once, all zeroes so far (privacy toggle, a call holding capture): 麦克风没有声音; a later nonzero sample is still the onset. */
    fun silent(): Boolean {
        if (toldSilent || before != null || read < silentAfter) return false
        toldSilent = true
        return true
    }

    /** True for the window the first nonzero sample is in, once. */
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
