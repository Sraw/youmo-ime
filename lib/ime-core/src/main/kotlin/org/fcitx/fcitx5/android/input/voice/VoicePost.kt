/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * What is typed from the stretches a hold heard: each one cleaned ([VoiceText.clean]), then joined.
 * The one place the voice text is put together, for the evaluation (`ime-eval voice-post`, which
 * replays recorded stretches through it), so a change here is measured before it ships; the app's
 * [VoiceHold.text] joins its stretches with [join] alone until the layers go into it.
 */
object VoicePost {

    /** A stretch the VAD cut, as the recognizer wrote it, and where it was in the recording (ms). */
    class Stretch(val raw: String, val startMs: Long = 0, val endMs: Long = 0)

    /** What is done to the stretches put together, each layer on its own (060-voice-post.md §4); all on by default. */
    data class Layers(
        /** C: the filler sounds and stammered words out ([VoiceDisfluency]) */
        val tidy: Boolean = true,
        /** F: a stretch that said a command alone done, not typed ([VoiceCommand]) */
        val commands: Boolean = true,
    )

    /** [stretches] as typed. */
    fun text(stretches: List<Stretch>, layers: Layers = Layers()): String {
        var pieces = emptyList<String>()
        // what each stretch changed, for 删掉 to take back: the last thing said, words or a command
        val before = ArrayList<List<String>>()
        for (stretch in stretches) {
            val command = if (layers.commands) VoiceCommand.of(stretch.raw) else null
            if (command == VoiceCommand.Delete) {
                pieces = before.removeLastOrNull() ?: pieces
                continue
            }
            // a command is a step even when it changes nothing (好吗？ then 问号): 删掉 after it takes back nothing
            // said before it; a stretch with nothing typed (嗯。) is none
            val next = if (command == null) VoiceText.clean(stretch.raw)?.let { pieces + it } else command.on(pieces) ?: pieces
            if (next != null) {
                before += pieces
                pieces = next
            }
        }
        // over the whole: a word stammered across a pause is two stretches
        val joined = join(pieces)
        return if (layers.tidy) VoiceDisfluency.clean(joined) else joined
    }

    private const val PAUSE_MARKS = "，、；：,;:"
    private const val END_MARKS = "。？！.?!$PAUSE_MARKS"

    // [pieces] with a mark in place of the one the words before ended with, those before a new line too (换行, 句号:
    // the full stop on the line's end, not the next's start), said first on its own; a new line after what ends
    // there, its pause's mark dropped (今天开会， -> 今天开会); null if nothing changes
    private fun VoiceCommand.on(pieces: List<String>): List<String>? = when (this) {
        is VoiceCommand.Mark -> {
            val words = pieces.indexOfLast { it.isNotBlank() }
            when {
                pieces.isEmpty() -> listOf("$mark")
                words < 0 -> null
                else -> pieces.toMutableList().apply { this[words] = this[words].trimEnd { it in END_MARKS } + mark }
            }
        }
        is VoiceCommand.Lines ->
            pieces.toMutableList().apply {
                if (isNotEmpty()) this[lastIndex] = last().trimEnd { it in PAUSE_MARKS }
                add("\n".repeat(lines))
            }
        VoiceCommand.Delete -> null
    }

    /** Cleaned [pieces] one after another. */
    fun join(pieces: List<String>): String =
        pieces.fold("") { acc, piece -> if (wordsMeet(acc, piece)) "$acc $piece" else acc + piece }

    // two stretches of English (or numbers) side by side keep a space between them
    private fun wordsMeet(before: String, after: String) =
        before.isNotEmpty() && before.last().isAsciiWord() && after.first().isAsciiWord()

    private fun Char.isAsciiWord() = this < '\u0080' && isLetterOrDigit()
}
