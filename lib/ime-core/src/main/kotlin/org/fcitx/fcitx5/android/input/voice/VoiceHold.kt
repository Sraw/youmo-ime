/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * Hold to talk, on the space key as Sogou's and WeChat's keyboards have it: the long press opens
 * the microphone, what is said is typed when the finger lifts, and lifting it after sliding up
 * [cancelDistance] or more drops it. What is said shows as it is recognized (VoiceLive) and is
 * typed when the finger lifts; this is the finger's part: its zone, and what lifting it does.
 */
class VoiceHold(private val cancelDistance: Float) {

    enum class Zone { Talk, Cancel }

    var zone = Zone.Talk
        private set

    /** the finger lifted: what is heard now is the last of it */
    var released = false
        private set

    /** The finger [dy] below where it went down (up is negative); whether the zone changed. */
    fun moved(dy: Float): Boolean {
        if (released) return false
        val now = if (-dy >= cancelDistance) Zone.Cancel else Zone.Talk
        if (now == zone) return false
        zone = now
        return true
    }

    /** What the finger lifting does: type what was said, or drop it. */
    fun release(): Boolean {
        released = true
        return zone == Zone.Talk
    }
}
