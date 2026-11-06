/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.editing.toEditorTraits

/**
 * One hold to talk, whatever is held (the space key, the panel's microphone): the microphone open
 * from the press, [VoiceHold] deciding what the finger does, what is heard composing in the field
 * as it is said, what was said [commit]ted once it lifts (its end as the field has it, [VoiceEnd]),
 * into the field it was said for and never a password. Tells [ui] on the main thread, until
 * [Ui.ended]. Used from the main thread.
 */
class VoiceHoldSession(
    private val service: FcitxInputMethodService,
    private val fcitx: HostConnection,
    /** VoiceHold's: how far the finger goes to drop it, and whether around a round button */
    private val hold: VoiceHold,
    private val ui: Ui,
    private val commit: (String) -> Unit,
) {
    interface Ui {
        /** the microphone really on (its first sample not zero): 正在听 and a short vibration, 准备中 before */
        fun ready()

        /** 2 s of zeroes, none other yet: 麦克风没有声音 until [ready] */
        fun silent()

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
    private var listener: Listening? = null

    // what is heard shows in the field as it is said: not a password's, nor over a word the field
    // composes already (English's, which fcitx still has); there only the hold's own view shows it
    private val composes = service.voiceComposes
    // something composing in the field now, ours: taken away if nothing is typed
    private var composing = false

    var over = false
        private set

    /** ended by the 15 s give-up, not by the recognizer: the panel's waiting Enter is dropped (VoiceTailEnter) */
    var gaveUp = false
        private set

    /** lifted, the rest the recognizer's: the finger has nothing more to say */
    val released get() = hold.released

    private val events = object : VoiceListener.Events {
        override fun loaded() = Unit

        // lifted already (识别中… showing), or over: nothing to tell
        override fun ready() {
            if (!over && !hold.released) ui.ready()
        }

        override fun silent() {
            if (!over && !hold.released) ui.silent()
        }

        override fun heard(text: String) {
            if (over) return
            ui.heard(text)
            if (composes && service.currentInputEditorInfo === field) {
                service.composeVoice(text)
                composing = text.isNotEmpty()
            }
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
        listener = (listening?.invoke(events) ?: VoiceListener(
            service.assets,
            events,
            hotwords = { VoiceEngine.userHotwords(fcitx, habits = AppPrefs.getInstance().internal.useHabits.getValue()) },
            language = VoiceEngine.language(fcitx),
            polish = AppPrefs.getInstance().keyboard.voicePolish.getValue(),
        )).also {
            it.load()
            it.start()
        }
    }

    /** The finger moved: as [VoiceHold.moved]. */
    fun moved(dx: Float, dy: Float) {
        if (!over && hold.moved(dx, dy)) ui.zone(hold.zone == VoiceHold.Zone.Cancel)
    }

    /** The finger up at [at] (its event's uptime, ms): the tail after it counts from there. */
    fun lift(at: Long) {
        if (over || hold.released) return
        if (!hold.release()) {
            end(null)
            return
        }
        ui.recognizing()
        // the models not coming in, or a stretch not coming back: not waiting for good
        main.postDelayed({
            gaveUp = true
            end(R.string.voice_hold_nothing)
        }, FINISH_MS)
        listener?.finish(at) { said ->
            // still the field it was said for: not text landing somewhere else
            val text = VoiceCommit.typed(
                said?.let { ended(it) }.orEmpty(),
                sameField = service.currentInputEditorInfo === field,
                passwordField = service.inPasswordField,
            )
            if (text != null) {
                // commit before end: the panel's waiting Enter goes out from Ui.ended, after the
                // text's own fcitx job (VoiceWindow, VoiceTailEnter); ended first, it would beat the
                // text. It replaces what composes in the field: not taken away first, no flicker
                composing = false
                commit(text)
                end(null)
            } else {
                end(R.string.voice_hold_nothing)
            }
        }
    }

    /**
     * A new press after the lift (the user's rule): the tail ends now, what was said is still
     * typed, the press itself counts for nothing. Recognizing already, it changes nothing.
     */
    fun pressedAgain() {
        if (!over && hold.released) listener?.cut()
    }

    /** Dropped, whatever was heard: another field, the keyboard gone, the touch taken away. */
    fun drop() = end(null)

    // [text]'s end as the field it goes into has it, by the user's choice (语音输入 → 句末句号)
    private fun ended(text: String): String {
        val kind = field?.let { VoiceEnd.field(it.toEditorTraits(service.packageName), it.packageName) } ?: VoiceEnd.Field.OTHER
        return VoiceEnd.apply(text, kind, AppPrefs.getInstance().keyboard.voiceEndMark.getValue().mode)
    }

    private fun end(@StringRes message: Int?) {
        if (over) return
        over = true
        main.removeCallbacksAndMessages(null)
        // dropped, nothing heard, given up: what composed goes, in the field it composed in only
        if (composing && service.currentInputEditorInfo === field) service.composeVoice("")
        composing = false
        listener?.close()
        listener = null
        ui.ended(message)
    }

    /** What [VoiceListener] is to a session: the microphone and the recognizer behind [VoiceListener.Events]. */
    interface Listening {
        fun load()
        fun start()
        /** the microphone closed a tail after [liftedAt] (uptime, ms), all of it recognized, then [done] with what to type (null: nothing) */
        fun finish(liftedAt: Long, done: (String?) -> Unit)

        /** the microphone closed now, what was heard still recognized */
        fun cut()
        fun close()
    }

    companion object {
        /**
         * The JVM's UAT tests' recognizer: what is heard told straight to the events, the chain
         * after the recognizer (VoiceHold, VoiceEnd, VoiceCommit, the commit) the app's own.
         * sherpa-onnx and the microphone are a device's.
         */
        @VisibleForTesting
        internal var listening: ((VoiceListener.Events) -> Listening)? = null

        private const val FINISH_MS = 15_000L

        /** how long a message shows before the hold's view goes */
        const val MESSAGE_MS = 1200L
    }
}
