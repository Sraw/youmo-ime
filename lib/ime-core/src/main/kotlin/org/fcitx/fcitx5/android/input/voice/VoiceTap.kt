/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * Tap to talk, the bar's microphone (060-voice-tap-to-talk.md, the user's decisions 2026-11-06):
 * tapped, it listens on with no finger held, a mask over the keys, till it is stopped. What each
 * press or event does to it.
 *
 * Stopping keeps the microphone on for the tail ([VoiceCaptureEnd.tail], as a lift), then types
 * what is left; pressed again meanwhile, the tail ends at once (as a press after a lift, VO-25).
 * × drops what is not typed yet. The keyboard hidden stops it as a tap does, what is left typed
 * straight into the field (still its, its connection still good) once recognized. Another field
 * types what was heard so far at once, into the field it was said in: its connection is about to go.
 * To send what was said: stop it, then the keyboard's Enter (no Enter on the mask: the user's
 * decision 2026-11-06).
 */
class VoiceTap {

    enum class Press {
        /** the bar's microphone again */
        MIC,

        /** anywhere on the mask */
        MASK,

        /** a press off the mask (the bar's other buttons, the candidates): it then acts as usual */
        OUTSIDE,

        /** the mask's ×: cancel */
        CANCEL,

        /** [QUIET_MS] with nothing new heard */
        QUIET,

        /** the keyboard hidden, the field kept */
        HIDDEN,

        /** another field, or the keyboard's view made anew */
        INPUT_ENDED,
    }

    enum class Act {
        NONE,

        /** the microphone closed a tail from now; what is left typed once recognized */
        STOP,

        /** stopping already: the tail ends now */
        CUT,

        /** the keyboard hidden: stopped if not yet, what is left typed straight into the field, not through the engine */
        HIDE,

        /** what is not typed yet dropped, at once */
        DROP,

        /** what was heard so far typed at once, the rest not waited for */
        TYPE_NOW,
    }

    enum class State { LISTENING, STOPPING, OVER }

    var state = State.LISTENING
        private set

    /** What [press] does now. */
    fun press(press: Press): Act {
        val act = when (state) {
            State.OVER -> Act.NONE
            State.LISTENING -> when (press) {
                Press.CANCEL -> Act.DROP
                Press.INPUT_ENDED -> Act.TYPE_NOW
                Press.HIDDEN -> Act.HIDE
                else -> Act.STOP
            }
            State.STOPPING -> when (press) {
                Press.CANCEL -> Act.DROP
                Press.INPUT_ENDED -> Act.TYPE_NOW
                Press.HIDDEN -> Act.HIDE
                Press.QUIET -> Act.NONE
                else -> Act.CUT
            }
        }
        when (act) {
            Act.STOP, Act.HIDE -> state = State.STOPPING
            Act.DROP, Act.TYPE_NOW -> state = State.OVER
            Act.NONE, Act.CUT -> {}
        }
        return act
    }

    /** The session ended on its own: typed, nothing heard, failed or given up. */
    fun ended() {
        state = State.OVER
    }

    companion object {
        /** how long nothing new heard stops it (the user's 60 s): a talk forgotten does not listen on for good */
        const val QUIET_MS = 60_000L
    }
}
