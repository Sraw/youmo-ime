/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * Voice input as the panel shows it: the models loading, then listening; a stretch of speech heard
 * and, when it ends, recognized and typed. The microphone stays open until the user pauses it or
 * leaves the panel, as Sogou's does: what was said goes in a stretch at a time, at each pause.
 * The recognizer, the microphone and the panel are the app's; this decides what each event means,
 * and above all when the microphone may be open: [wantsMicrophone], nothing else.
 */
class VoiceSession {

    enum class State {
        /** the models being read in, to listen once they are */
        Loading,

        /** the microphone open, nothing being said */
        Listening,

        /** something being said: recognized when it stops */
        Hearing,

        /** the microphone closed, by the user or by the keyboard going; a tap opens it again */
        Paused,

        /** no microphone permission, or the models would not load */
        Failed
    }

    enum class Failure { NoPermission, NoModel, NoMicrophone }

    var state = State.Loading
        private set

    var failure: Failure? = null
        private set

    /** the models are in; they go when the panel lets the recognizer go ([unloaded]) */
    var ready = false
        private set

    /** stretches being recognized: the panel says so until the last is typed */
    var pending = 0
        private set

    /** the models are in: listen, unless paused meanwhile */
    fun loaded() {
        ready = true
        if (state == State.Loading) state = State.Listening
    }

    /** the recognizer let go: a tap reads the models in again */
    fun unloaded() {
        ready = false
        pending = 0
        if (state == State.Listening || state == State.Hearing || state == State.Loading) state = State.Paused
    }

    fun failed(why: Failure) {
        failure = why
        state = State.Failed
    }

    /** back to the start, the models to be read in again: a permission granted after all */
    fun reset() {
        state = State.Loading
        failure = null
        ready = false
        pending = 0
    }

    /** The keyboard hidden or the field left: nothing is to be heard until a tap. */
    fun pause() {
        if (state != State.Failed) state = State.Paused
    }

    fun speechStarted() {
        if (state == State.Listening) state = State.Hearing
    }

    /** a stretch of speech ended and is being recognized */
    fun speechEnded() {
        pending++
        if (state == State.Hearing) state = State.Listening
    }

    /** What to type of a stretch recognized: [VoiceText.clean]ed, null for nothing. */
    fun recognized(raw: String): String? {
        if (pending > 0) pending--
        return VoiceText.clean(raw)
    }

    /** The big button: pause what is open or about to be, open what is paused. */
    fun tap() {
        state = when (state) {
            State.Listening, State.Hearing, State.Loading -> State.Paused
            State.Paused -> if (ready) State.Listening else State.Loading
            State.Failed -> State.Failed
        }
    }

    val listening get() = state == State.Listening || state == State.Hearing

    /** the models wanted: being read in, or in use */
    val wantsModels get() = state == State.Loading || listening
}
