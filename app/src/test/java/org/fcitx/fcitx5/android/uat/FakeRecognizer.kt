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
 * What [said] holds is heard as the talk stops, [held] the tail still being recorded until [release] or a [cut].
 * As VoiceListener's, without the tagger unless [polish] gives a stream.
 */
class FakeRecognizer : VoiceSession.Listening {
    lateinit var events: VoiceListener.Events
    val said = mutableListOf<String>()

    var polish: (() -> VoicePolishStream)? = null

    private var live = VoiceLive()
    var opened = 0
    var held = false
    var cuts = 0
    var finishes = 0
    var closes = 0
    var stoppedAt: Long? = null

    private var closed = false

    /** a recognizer that never comes back, even cut: the session's 15 s give-up ends it */
    var stuck = false
    private var waiting: ((String?, Boolean) -> Unit)? = null

    var finishRaw = false

    var polishNotLoaded = false

    override val polishFailure
        get() = when {
            polishNotLoaded -> VoiceSession.PolishFailure.LOAD
            finishRaw -> VoiceSession.PolishFailure.RUN
            else -> null
        }

    // the causes said already are the process's
    init {
        VoiceSession.polishHinted.clear()
    }

    /** false: the first sample not zero only when [on] says so */
    var warm = true

    private var restarting: Pair<Int, VoiceLive.Mark?>? = null
    override var utterance = 0

    /** the recognizer not told yet of the app taking the text: what it hears is of the last utterance */
    var lagging = false

    override fun restart(to: Int, upTo: VoiceLive.Mark?) {
        val last = restarting
        restarting = to to (upTo ?: last?.takeIf { it.first > live.utterance }?.second)
    }

    private fun live() = live.also { l -> if (!lagging) restarting?.let { l.restart(it.first, it.second) } }

    override fun load() = Unit
    override fun start() {
        opened++
        live = VoiceLive(polish?.invoke())
        restarting = null
        utterance = 0
        closed = false
        if (warm) Handler(Looper.getMainLooper()).post { on() }
    }

    fun on() = events.ready()

    fun silent() = events.silent()

    fun fail(why: VoiceListener.Failure) = events.failed(why)

    fun interrupted() = events.interrupted()

    fun heard(raw: String) = events.heard(live().closed(raw))

    fun hearing(raw: String) = events.heard(live().heard(raw))

    override fun finish(stoppedAt: Long, done: (said: String?, raw: Boolean) -> Unit) {
        finishes++
        this.stoppedAt = stoppedAt
        waiting = done
        if (!held) release()
    }

    fun release() {
        said.forEach(::heard)
        said.clear()
        val done = waiting
        waiting = null
        utterance = live().utterance
        if (!closed) done?.invoke(if (finishRaw) live.raw else live.finished(), finishRaw)
    }

    // what was heard comes back from another thread
    override fun cut() {
        cuts++
        if (waiting != null && !stuck) Handler(Looper.getMainLooper()).post { release() }
    }

    override fun close() {
        closes++
        closed = true
    }
}
