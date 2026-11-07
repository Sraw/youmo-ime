/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * [VoiceQuiet] as VoiceListener wires it: the capture thread's windows in, the recognizer's
 * segments out as typed, or "" where they were the room. Here so the wiring is tested
 * (VoiceQuietSegmentsTest): every window and every text of the listener passes through it.
 */
class VoiceQuietSegments(private val quiet: VoiceQuiet = VoiceQuiet()) {

    /** samples handed to the recognizer, from the microphone's opening; this segment's from [start] */
    var fed = 0L
        private set
    private var start = 0L

    /** The capture thread's window: its raw samples, before the gain; [speech] the VAD's say (null: none). */
    fun window(samples: FloatArray, n: Int, speech: Boolean?) = quiet.window(samples, n, speech)

    /** [n] samples handed to the recognizer. */
    fun fed(n: Int) {
        fed += n
    }

    /** The recognizer's endpoint: the next segment starts here. */
    fun next() {
        start = fed
    }

    /** The segment's [text] so far or at its end, or "" if it was the room's. */
    fun heard(text: String): String = if (quiet.keeps(text, start, fed)) text else ""
}
