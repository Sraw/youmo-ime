/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/** Hold to talk on the space key (Sogou, WeChat): the finger's zone, and what lifting does; lifting after sliding up [cancelDistance] drops it. */
class VoiceHold(private val cancelDistance: Float) {

    enum class Zone { Talk, Cancel }

    var zone = Zone.Talk
        private set

    /** What is heard now is the last of it. */
    var released = false
        private set

    /** Up is negative; whether the zone changed. */
    fun moved(dy: Float): Boolean {
        if (released) return false
        val now = if (-dy >= cancelDistance) Zone.Cancel else Zone.Talk
        if (now == zone) return false
        zone = now
        return true
    }

    /** Type what was said, or drop it. */
    fun release(): Boolean {
        released = true
        return zone == Zone.Talk
    }
}
