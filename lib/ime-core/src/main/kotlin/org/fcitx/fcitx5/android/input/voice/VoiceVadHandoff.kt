/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * The microphone's windows to the VAD, which may come in after the microphone is on: held in a
 * [VoiceBacklog] (at most [most] samples) until it is in ([arrived]), then fed in the order read;
 * each stretch it cuts handed on ([Events.stretch]) with what the backlog dropped before it came,
 * the VAD's positions being behind the record's by that many. At the opening's end ([end]) a VAD
 * come only then still hears what was held (a lift or a cut types what was said); [close] drops only
 * what is still held, so a VAD already in still flushes its last stretch at [end] and the caller
 * ignores what comes after its close. One per
 * microphone opening, on the capture thread.
 */
class VoiceVadHandoff<S>(most: Int, private val events: Events<S>) {

    /** The VAD as this needs it (sherpa-onnx's Vad in the app); [S] a stretch it cut. */
    interface Vad<S> {
        fun accept(samples: FloatArray)
        fun isSpeech(): Boolean
        fun empty(): Boolean
        fun front(): S
        fun pop()
        fun flush()

        /** where [segment] starts in the samples fed to this VAD */
        fun start(segment: S): Int
    }

    interface Events<S> {
        /** speech heard where there was none */
        fun speechStarted()

        /** a stretch cut, popped already; [dropped] the samples the backlog dropped before the VAD came */
        fun stretch(segment: S, dropped: Long)
    }

    private val backlog = VoiceBacklog(most)

    /** the VAD once in */
    var vad: Vad<S>? = null
        private set

    private var speaking = false

    /** the debug log's: the last stretch's start in the record (-1 for none), and how many were cut */
    var lastStart = -1L
        private set
    var segments = 0
        private set

    /** samples the backlog dropped for want of room before the VAD came */
    val dropped get() = backlog.dropped

    /** One window read: to the VAD if it is in, else held. */
    fun window(samples: FloatArray) {
        val toVad = backlog.window(samples)
        vad?.let { feed(it, toVad) }
    }

    /** The VAD in: what was held first, in order, then each window as read. */
    fun arrived(made: Vad<S>) {
        check(vad == null) { "a VAD in already" }
        vad = made
        feed(made, backlog.start())
    }

    /** Closed for good before the VAD came: what was held goes, nothing is to be heard of it. */
    fun close() = backlog.drop()

    /**
     * The opening over: [late] a VAD come in only now (null for none), fed what was held. The last
     * stretch flushed out and handed on. The VAD used, for its release; null without one.
     */
    fun end(late: Vad<S>?): Vad<S>? {
        val used = vad ?: late?.also(::arrived) ?: return null
        used.flush()
        drain(used)
        return used
    }

    private fun feed(vad: Vad<S>, windows: List<FloatArray>) {
        for (samples in windows) {
            vad.accept(samples)
            val now = vad.isSpeech()
            if (now && !speaking) events.speechStarted()
            speaking = now
            drain(vad)
        }
    }

    // front() read once: the app's copies the whole stretch over JNI
    private fun drain(vad: Vad<S>) {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            lastStart = vad.start(segment) + backlog.dropped
            segments++
            events.stretch(segment, backlog.dropped)
        }
    }
}
