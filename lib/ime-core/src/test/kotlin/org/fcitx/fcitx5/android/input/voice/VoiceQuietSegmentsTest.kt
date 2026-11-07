/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceQuietSegmentsTest {

    // -60 dBFS: the mean square 1e-6, a room the gain lifts to 「I'm sorry.」
    private val room = FloatArray(512) { 1e-3f }

    // as VoiceListener: each window to the quiet, then to the recognizer
    private fun VoiceQuietSegments.hear(windows: Int, speech: Boolean? = false) = repeat(windows) {
        window(room, room.size, speech)
        fed(room.size)
    }

    @Test
    fun theRoomsSorryIsNotTypedChineseIs() {
        val segments = VoiceQuietSegments()
        segments.hear(60)
        assertEquals("", segments.heard("I'm sorry."))
        segments.next()
        segments.hear(60)
        assertEquals("你好", segments.heard("你好"))
    }

    @Test
    fun withNoDetectorEverythingIsTyped() {
        val segments = VoiceQuietSegments()
        segments.hear(60, speech = null)
        assertEquals("I'm sorry.", segments.heard("I'm sorry."))
    }
}
