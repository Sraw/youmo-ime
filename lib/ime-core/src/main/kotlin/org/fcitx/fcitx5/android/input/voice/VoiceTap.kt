/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * Tap to talk: listens with no finger held, a mask over the keys, till stopped. Stopping keeps the microphone on for
 * the tail, then types what is left; a press meanwhile ends the tail. Hidden: stopped, the rest typed straight into the
 * field. Another field: what was heard so far typed at once (its connection is about to go).
 */
class VoiceTap {

    enum class Press {
        MIC,

        MASK,

        /** Off the mask (the bar's other buttons, the candidates): it then acts as usual. */
        OUTSIDE,

        /** [QUIET_MS] with nothing new heard. */
        QUIET,

        /** The field kept. */
        HIDDEN,

        /** Or the keyboard's view made anew. */
        INPUT_ENDED,
    }

    enum class Act {
        NONE,

        /** The microphone closed a tail from now; what is left typed once recognized. */
        STOP,

        /** Stopping already: the tail ends now. */
        CUT,

        /** Stopped if not yet, what is left typed straight into the field, not through the engine. */
        HIDE,

        /** The rest not waited for. */
        TYPE_NOW,
    }

    enum class State { LISTENING, STOPPING, OVER }

    var state = State.LISTENING
        private set

    fun press(press: Press): Act {
        val act = when (state) {
            State.OVER -> Act.NONE
            State.LISTENING -> when (press) {
                Press.INPUT_ENDED -> Act.TYPE_NOW
                Press.HIDDEN -> Act.HIDE
                else -> Act.STOP
            }
            State.STOPPING -> when (press) {
                Press.INPUT_ENDED -> Act.TYPE_NOW
                Press.HIDDEN -> Act.HIDE
                Press.QUIET -> Act.NONE
                else -> Act.CUT
            }
        }
        when (act) {
            Act.STOP, Act.HIDE -> state = State.STOPPING
            Act.TYPE_NOW -> state = State.OVER
            Act.NONE, Act.CUT -> {}
        }
        return act
    }

    /** Typed, nothing heard, failed or given up. */
    fun ended() {
        state = State.OVER
    }

    companion object {
        /** A forgotten talk does not listen on for good. */
        const val QUIET_MS = 60_000L
    }
}
