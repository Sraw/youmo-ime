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
 *
 * [around]: held on a round button instead (the voice panel's microphone), the finger measured
 * from its centre: out of the circle of [cancelDistance], whichever way, drops it.
 */
class VoiceHold(private val cancelDistance: Float, private val around: Boolean = false) {

    enum class Zone { Talk, Cancel }

    var zone = Zone.Talk
        private set

    /** the finger lifted: what is heard now is the last of it */
    var released = false
        private set

    private val pieces = mutableListOf<String>()

    /**
     * The finger [dx] right of and [dy] below where it went down, or the button's centre if
     * [around] (left and up are negative); whether the zone changed.
     */
    fun moved(dx: Float, dy: Float): Boolean {
        if (released) return false
        val out = if (around) dx * dx + dy * dy > cancelDistance * cancelDistance else -dy >= cancelDistance
        val now = if (out) Zone.Cancel else Zone.Talk
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
