/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.input.FcitxInputMethodService

/**
 * One hold to talk, whatever is held (the space key, the panel's microphone): the microphone open
 * from the press, [VoiceHold] deciding what the finger does, what was said [commit]ted once it
 * lifts, into the field it was said for and never a password. Tells [ui] on the main thread, until
 * [Ui.ended]. Used from the main thread.
 */
class VoiceHoldSession(
    private val service: FcitxInputMethodService,
    private val fcitx: FcitxConnection,
    /** VoiceHold's: how far the finger goes to drop it, and whether around a round button */
    private val hold: VoiceHold,
    private val ui: Ui,
    private val commit: (String) -> Unit,
) {
    interface Ui {
        /** what was said so far, as it will be typed */
        fun heard(text: String)

        /** how loud, 0 to 1 */
        fun level(level: Float)

        /** slid into the zone where lifting drops it, or back out */
        fun zone(cancel: Boolean)

        /** lifted: the last of it being recognized */
        fun recognizing()

        /** Over: typed or dropped ([message] null), or not ([message] to show a moment). */
        fun ended(@StringRes message: Int?)
    }


    // the field it is said for
    private val field = service.currentInputEditorInfo
    private val main = Handler(Looper.getMainLooper())
    private var listener: VoiceListener? = null

    var over = false
        private set

    /** lifted, the rest the recognizer's: the finger has nothing more to say */
    val released get() = hold.released

    private val events = object : VoiceListener.Events {
        override fun loaded() = Unit
        override fun speechStarted() = Unit
        override fun speechEnded() = Unit

        override fun recognized(text: String) {
            hold.heard(text)
            ui.heard(hold.text)
        }

        override fun level(level: Float) = ui.level(level)

        override fun failed(why: VoiceListener.Failure) = end(
            when (why) {
                VoiceListener.Failure.NoModel -> R.string.voice_no_model
                VoiceListener.Failure.NoMicrophone -> R.string.voice_no_microphone
            }
        )
    }

    /** The press: the microphone opens now, the models load meanwhile. */
    fun begin() {
        listener = VoiceListener(
            service.assets,
            events,
            hotwords = { VoiceEngine.userHotwords(fcitx) },
            boundaries = { VoiceEngine.wordBoundaries(fcitx, it) },
        ).also {
            it.load()
            it.start()
        }
    }

    /** The finger moved: as [VoiceHold.moved]. */
    fun moved(dx: Float, dy: Float) {
        if (!over && hold.moved(dx, dy)) ui.zone(hold.zone == VoiceHold.Zone.Cancel)
    }

    fun lift() {
        if (over || hold.released) return
        if (!hold.release()) {
            end(null)
            return
        }
        ui.recognizing()
        // the models not coming in, or a stretch not coming back: not waiting for good
        main.postDelayed({ end(R.string.voice_hold_nothing) }, FINISH_MS)
        listener?.finish {
            val text = hold.text
            // still the field it was said for: not text landing somewhere else
            if (text.isNotEmpty() && service.currentInputEditorInfo === field && !service.inPasswordField) {
                commit(text)
                end(null)
            } else {
                end(R.string.voice_hold_nothing)
            }
        }
    }

    /** Dropped, whatever was heard: another field, the keyboard gone, the touch taken away. */
    fun drop() = end(null)

    private fun end(@StringRes message: Int?) {
        if (over) return
        over = true
        main.removeCallbacksAndMessages(null)
        listener?.close()
        listener = null
        ui.ended(message)
    }

    companion object {
        private const val FINISH_MS = 15_000L

        /** how long a message shows before the hold's view goes */
        const val MESSAGE_MS = 1200L
    }
}
