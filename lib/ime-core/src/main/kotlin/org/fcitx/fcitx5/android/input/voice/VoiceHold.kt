/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * Hold to talk, on the space key as Sogou's and WeChat's keyboards have it: the long press opens
 * the microphone, what is said is typed when the finger lifts, and lifting it after sliding up
 * [cancelDistance] or more drops it. What is heard meanwhile is shown, not typed: a stretch is
 * recognized at each pause, the last one once the finger lifts.
 */
class VoiceHold(private val cancelDistance: Float) {

    enum class Zone { Talk, Cancel }

    var zone = Zone.Talk
        private set

    /** the finger lifted: what is heard now is the last of it */
    var released = false
        private set

    private val pieces = mutableListOf<String>()

    /** The finger [dy] below where it went down (up is negative); whether the zone changed. */
    fun moved(dy: Float): Boolean {
        if (released) return false
        val now = if (-dy >= cancelDistance) Zone.Cancel else Zone.Talk
        if (now == zone) return false
        zone = now
        return true
    }

    /** A stretch recognized, as the recognizer wrote it. */
    fun heard(raw: String) {
        VoiceText.clean(raw)?.let { pieces += it }
    }

    /** What the finger lifting does: type what was said, or drop it. */
    fun release(): Boolean {
        released = true
        return zone == Zone.Talk
    }

    /** What was said so far, as typed: the stretches one after another. */
    val text: String
        get() = pieces.fold("") { acc, piece -> if (wordsMeet(acc, piece)) "$acc $piece" else acc + piece }

    // two stretches of English (or numbers) side by side keep a space between them
    private fun wordsMeet(before: String, after: String) =
        before.isNotEmpty() && before.last().isAsciiWord() && after.first().isAsciiWord()

    private fun Char.isAsciiWord() = this < '\u0080' && isLetterOrDigit()
}
