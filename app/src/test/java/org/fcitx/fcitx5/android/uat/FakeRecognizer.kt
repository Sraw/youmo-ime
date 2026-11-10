/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.os.Handler
import android.os.Looper
import org.fcitx.fcitx5.android.input.voice.VoiceListener
import org.fcitx.fcitx5.android.input.voice.VoiceLive
import org.fcitx.fcitx5.android.input.voice.VoicePolishStream
import org.fcitx.fcitx5.android.input.voice.VoiceSession

/**
 * The JVM UAT tests' recognizer ([VoiceSession.listening]): what [said] holds is heard as the talk
 * stops, each a segment closed, [heard] one before, [hearing] the segment being said; [opened]
 * times the microphone opened. [held]: the tail after the stop still being recorded, until
 * [release] or a [cut]. As VoiceListener's, without the tagger (语音整理 is a device's: the model is)
 * unless [polish] gives a stream, a test's own small tagger behind it.
 */
class FakeRecognizer : VoiceSession.Listening {
    lateinit var events: VoiceListener.Events
    val said = mutableListOf<String>()

    /** 语音整理's stream, one a talk; null: none */
    var polish: (() -> VoicePolishStream)? = null

    // one a talk, as a listener is
    private var live = VoiceLive()
    var opened = 0
    var held = false
    var cuts = 0
    var finishes = 0
    var closes = 0
    var stoppedAt: Long? = null

    // as VoiceListener's: once closed, nothing more told
    private var closed = false

    /** a recognizer that never comes back, even cut: the session's 15 s give-up ends it */
    var stuck = false
    private var waiting: ((String?, Boolean) -> Unit)? = null

    /** the polish failing at the end: what was said comes back as the raw text, as VoiceListener's then */
    var finishRaw = false

    /** 语音整理's model not read in: the raw text throughout, as VoiceListener's then */
    var polishNotLoaded = false

    override val polishFailure
        get() = when {
            polishNotLoaded -> VoiceSession.PolishFailure.LOAD
            finishRaw -> VoiceSession.PolishFailure.RUN
            else -> null
        }

    // a test's own: the causes said already are the process's
    init {
        VoiceSession.polishHinted.clear()
    }

    /** the microphone's first sample not zero comes as it opens; false: only when [on] says so */
    var warm = true

    override fun load() = Unit
    override fun start() {
        opened++
        live = VoiceLive(polish?.invoke())
        closed = false
        // from the capture thread, as VoiceListener's
        if (warm) Handler(Looper.getMainLooper()).post { on() }
    }

    /** the microphone really on */
    fun on() = events.ready()

    /** 2 s of zeroes */
    fun silent() = events.silent()

    /** a segment closed at an endpoint, the talk going on */
    fun heard(raw: String) = events.heard(live.closed(raw))

    /** the segment being said, so far */
    fun hearing(raw: String) = events.heard(live.heard(raw))

    override fun finish(stoppedAt: Long, done: (said: String?, raw: Boolean) -> Unit) {
        finishes++
        this.stoppedAt = stoppedAt
        waiting = done
        if (!held) release()
    }

    /** the tail recorded: what was said heard, then the session's done */
    fun release() {
        said.forEach(::heard)
        said.clear()
        val done = waiting
        waiting = null
        if (!closed) done?.invoke(if (finishRaw) live.raw else live.finished(), finishRaw)
    }

    // as VoiceListener's: the capture ends, and what was heard comes back from another thread
    override fun cut() {
        cuts++
        if (waiting != null && !stuck) Handler(Looper.getMainLooper()).post { release() }
    }

    override fun close() {
        closes++
        closed = true
    }
}
