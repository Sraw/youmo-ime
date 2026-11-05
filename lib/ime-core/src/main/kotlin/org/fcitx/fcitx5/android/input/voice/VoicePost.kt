/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * What is typed from the stretches a hold heard: each one cleaned ([VoiceText.clean]), then joined.
 * The one place the voice text is put together, for the app ([VoiceHold.text]) and for the
 * evaluation (`ime-eval voice-post`, which replays recorded stretches through it), so a change here
 * is measured before it ships.
 */
object VoicePost {

    /** A stretch the VAD cut, as the recognizer wrote it, and where it was in the recording (ms). */
    class Stretch(val raw: String, val startMs: Long = 0, val endMs: Long = 0)

    /** [stretches] as typed. */
    fun text(stretches: List<Stretch>): String = join(stretches.mapNotNull { VoiceText.clean(it.raw) })

    /** Cleaned [pieces] one after another. */
    fun join(pieces: List<String>): String =
        pieces.fold("") { acc, piece -> if (wordsMeet(acc, piece)) "$acc $piece" else acc + piece }

    // two stretches of English (or numbers) side by side keep a space between them
    private fun wordsMeet(before: String, after: String) =
        before.isNotEmpty() && before.last().isAsciiWord() && after.first().isAsciiWord()

    private fun Char.isAsciiWord() = this < '\u0080' && isLetterOrDigit()
}
