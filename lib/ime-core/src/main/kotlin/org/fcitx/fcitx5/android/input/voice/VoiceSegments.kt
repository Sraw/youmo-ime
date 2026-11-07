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

    /** the result's first token, in seconds from the stream's start; null: none */
    fun firstTime(result: R): Float?
}

/**
 * The segments said in one talk: decoded as far as the recognizer can, shown as they change, each
 * closed at an endpoint, the room's (VoiceQuiet) neither shown nor typed. The capture thread feeds
 * [window]; the decoder the rest, in order.
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
    private val quiet: VoiceQuiet = VoiceQuiet(),
) {
    /** samples handed to the recognizer, from the microphone's opening; this segment's from [start] */
    private var fed = 0L
    private var start = 0L
    private var last = ""

    // what this segment showed: one held back as the room's, unchanged, is not told again
    // (each telling resets heard and the quiet's timer)
    private var shown = ""

    /** segments closed with something in them; the UAT's (VO-23/24): one, or the lift's offset is not measured */
    var kept = 0
        private set

    /** the first token's frame, from the microphone's opening: where the speech starts, for the UAT (VO-23/24) */
    var speechStart: Int? = null
        private set

    /** The capture thread's window: its raw samples (before the gain), [speech] the VAD's say (null: no VAD). */
    fun window(samples: FloatArray, n: Int, speech: Boolean?) = quiet.window(samples, n, speech)

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
                start = fed
            } else {
                val text = recognizer.text(recognizer.result())
                if (text != last) {
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
        if (recognizer.text(result).isBlank()) return
        // the room's: what showed of it goes, nothing is kept
        val text = heard(choose(result))
        // the first segment's timestamps count from the stream's start, the microphone's opening
        if (text.isNotEmpty() && kept++ == 0) speechStart = recognizer.firstTime(result)?.let { (it * sampleRate).toInt() }
        // the room's, nothing of it shown: nothing to tell
        if (text.isNotEmpty() || shown.isNotEmpty()) closed(text)
        shown = ""
    }

    private fun heard(text: String) = if (quiet.keeps(text, start, fed)) text else ""
}
