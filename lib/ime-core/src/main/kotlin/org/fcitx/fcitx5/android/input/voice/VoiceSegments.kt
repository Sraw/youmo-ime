/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/** sherpa-onnx's OnlineRecognizer and OnlineStream in the app, a script in the tests. */
interface VoiceRecognition<R> {
    fun accept(samples: FloatArray)
    fun inputFinished()
    fun isReady(): Boolean
    fun decode()
    fun isEndpoint(): Boolean
    fun reset()
    fun result(): R
    fun text(result: R): String

    /** Seconds from the stream's last [reset]; null: none. */
    fun firstTime(result: R): Float?
}

/** One talk's segments, each closed at an endpoint; the room's neither shown nor typed ([quiet], fed by the capture thread). */
class VoiceSegments<R>(
    private val recognizer: VoiceRecognition<R>,
    private val sampleRate: Int,
    private val choose: suspend (R) -> String,
    private val live: suspend (String) -> Unit,
    private val closed: suspend (String) -> Unit,
    private val quiet: VoiceQuiet,
) {
    /** From the microphone's opening; this segment's from [start] for the quiet, its timestamps from [resetAt]. */
    private var fed = 0L
    private var start = 0L
    private var resetAt = 0L
    private var last = ""

    // one held back as the room's, unchanged, is not told again (each telling resets heard and the quiet's timer)
    private var shown = ""

    /** Segments closed with something in them; the UAT's: one, or the lift's offset is not measured. */
    var kept = 0
        private set

    /** For the UAT: late by what was undecoded at the reset before it (no more than a chunk). */
    var speechStart: Int? = null
        private set

    suspend fun accept(samples: FloatArray) {
        fed += samples.size
        recognizer.accept(samples)
        decode()
    }

    /** [padding] is not counted as heard. */
    suspend fun finish(padding: FloatArray) {
        recognizer.accept(padding)
        recognizer.inputFinished()
        decode()
        close()
    }

    private suspend fun decode() {
        while (recognizer.isReady()) {
            recognizer.decode()
            if (recognizer.isEndpoint()) {
                close()
                recognizer.reset()
                resetAt = fed
                // what was fed but not decoded at the endpoint is the next segment's: its windows judged with it
                start = maxOf(0L, fed - (sampleRate * UNDECODED_SECONDS).toLong())
            } else {
                val text = recognizer.text(recognizer.result())
                // a short word held back while it had no level is shown once it has, though unchanged
                if (text != last || (shown != text && text.isNotEmpty())) {
                    last = text
                    // the room's phrase shows no more than it is typed
                    val heard = heard(text)
                    if (heard == shown) continue
                    shown = heard
                    live(heard)
                }
            }
        }
    }

    private suspend fun close() {
        val result = recognizer.result()
        last = ""
        if (recognizer.text(result).isBlank()) {
            // what showed of it was taken back by the recognizer: taken back here too
            if (shown.isNotEmpty()) closed("")
            shown = ""
            return
        }
        // the room's: what showed of it goes, nothing is kept
        val text = heard(choose(result))
        // a segment's timestamps count from the reset before it
        if (text.isNotEmpty()) {
            if (kept == 0) speechStart = recognizer.firstTime(result)?.let { (resetAt + it * sampleRate).toInt() }
            kept++
        }
        if (text.isNotEmpty() || shown.isNotEmpty()) closed(text)
        shown = ""
    }

    private fun heard(text: String) = if (quiet.keeps(text, start, fed)) text else ""

    private companion object {
        // the backlog at an endpoint, a chunk and its context at most, well inside a silence endpoint's trailing silence (a length endpoint may let a room's short phrase through: accepted)
        const val UNDECODED_SECONDS = 0.5
    }
}
