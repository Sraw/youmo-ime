/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * The recognizer's stream as [VoiceSegments] drives it: sherpa-onnx's OnlineRecognizer and
 * OnlineStream in the app (VoiceListener's adapter), a script in the tests. [R] its result.
 */
interface VoiceRecognition<R> {
    fun accept(samples: FloatArray)
    fun inputFinished()
    fun isReady(): Boolean
    fun decode()
    fun isEndpoint(): Boolean
    fun reset()
    fun result(): R
    fun text(result: R): String

    /** the result's first token, in seconds from the stream's last [reset] (its start, before one); null: none */
    fun firstTime(result: R): Float?
}

/**
 * The segments said in one talk: decoded as far as the recognizer can, shown as they change, each
 * closed at an endpoint, the room's neither shown nor typed: [quiet], shared, the capture thread
 * feeding it each window; the decoder the rest, in order.
 *
 * [choose]: a closed segment's text (VoiceChoice); [live]: the segment so far, to show; [closed]:
 * a segment's text at its end ("" the room's, shown only if something of it showed).
 */
class VoiceSegments<R>(
    private val recognizer: VoiceRecognition<R>,
    private val sampleRate: Int,
    private val choose: suspend (R) -> String,
    private val live: suspend (String) -> Unit,
    private val closed: suspend (String) -> Unit,
    private val quiet: VoiceQuiet,
) {
    /**
     * samples handed to the recognizer, from the microphone's opening; this segment's from [start]
     * as the quiet judges it, its timestamps from [resetAt]
     */
    private var fed = 0L
    private var start = 0L
    private var resetAt = 0L
    private var last = ""

    // what this segment showed: one held back as the room's, unchanged, is not told again
    // (each telling resets heard and the quiet's timer)
    private var shown = ""

    /** segments closed with something in them; the UAT's (VO-23/24): one, or the lift's offset is not measured */
    var kept = 0
        private set

    /**
     * the first kept segment's first token, in samples from the microphone's opening: where the
     * speech starts, for the UAT (VO-23/24); late by what the recognizer had not decoded at the
     * reset before it (no more than a chunk)
     */
    var speechStart: Int? = null
        private set

    /** What the microphone read, gained, decoded as far as it can be. */
    suspend fun accept(samples: FloatArray) {
        fed += samples.size
        recognizer.accept(samples)
        decode()
    }

    /** The microphone closed: [padding] (not counted as heard), the last decoded and closed. */
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

    /** Its text chosen and kept, the next one starting empty. */
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
        // the room's, nothing of it shown: nothing to tell
        if (text.isNotEmpty() || shown.isNotEmpty()) closed(text)
        shown = ""
    }

    private fun heard(text: String) = if (quiet.keeps(text, start, fed)) text else ""

    private companion object {
        // the recognizer's backlog at an endpoint, a chunk and its context at most; well inside the
        // trailing silence a silence endpoint needs, so no speech before it is counted. After an
        // endpoint for length (rule 3, no silence) it is the segment before's speech: a short Latin
        // phrase the room said next may then be typed (accepted; exact would be the
        // recognizer's own start time of the segment, not exposed)
        const val UNDECODED_SECONDS = 0.5
    }
}
