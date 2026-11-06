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
 * One talk, whatever starts and stops it (the space key held, [VoiceHoldSession]; the bar's
 * microphone tapped, [VoiceTapOverlay]): the microphone open from [begin], what is heard composing
 * in the field as it is said, what was said [commit]ted once it [finish]es (its end as the field
 * has it, [VoiceEnd]), into the field it was said for and never a password. [asItGoes]: the settled
 * part typed as it is said ([VoiceTalk]), only the tail composing. Tells [ui] on the main thread,
 * until [Ui.ended]. Used from the main thread.
 *
 * What composes is taken from the field by others too: the app sending or clearing the text (it
 * took it: not typed again), the cursor moved or input restarted in the field (taken away: it
 * composes anew where the cursor is, on the next heard).
 */
class VoiceSession(
    private val service: FcitxInputMethodService,
    private val fcitx: HostConnection,
    private val ui: Ui,
    asItGoes: Boolean,
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

        /** stopped: the last of it being recognized */
        fun recognizing()

        /** Over: typed or dropped ([message] null), or not ([message] to show a moment). */
        fun ended(@StringRes message: Int?)
    }

    // the field it is said for: the same one after input restarts in it (another EditorInfo, the same app and view id)
    private val field = service.currentInputEditorInfo
    private val main = Handler(Looper.getMainLooper())
    private var listener: Listening? = null

    /**
     * What is heard shows in the field as it is said: not a password's, nor over a word the field
     * composes already (English's, which fcitx still has); there only the session's own view shows it.
     */
    val composes = service.voiceComposes

    // typing as it is said needs the field's composing text: not over fcitx's
    private val asItGoes = asItGoes && composes
    private val talk = VoiceTalk(this.asItGoes)

    // something composing in the field now, ours: taken away if nothing is typed
    private var composing = false

    // the keyboard hidden ([hidden]): nothing composes, what is left goes straight into the field
    private var direct = false

    var over = false
        private set

    /** stopped (the finger lifted, the microphone tapped): the rest is the recognizer's */
    var stopped = false
        private set

    val sameField get() = service.sameVoiceField(field)

    private val events = object : VoiceListener.Events {
        override fun loaded() = Unit

        // stopped already (识别中… showing), or over: nothing to tell
        override fun ready() {
            if (!over && !stopped) ui.ready()
        }

        override fun silent() {
            if (!over && !stopped) ui.silent()
        }

        override fun heard(heard: VoiceLive.Heard) {
            if (over) return
            // the field composes the engine's text now (another finger typing): nothing typed as it is
            // said meanwhile, nor its text made voice's; the keyboard hidden: nothing composes
            val inField = composes && !direct && sameField && service.voiceOwnsComposing
            val step = talk.heard(heard, typing = inField)
            ui.heard(heard.text)
            if (inField) {
                // put in at once, in place of what composed: then the tail composes after it
                service.voiceHeard(step.type, step.composing)
                composing = step.composing.isNotEmpty()
            }
        }

        override fun level(level: Float) = ui.level(level)

        override fun failed(why: VoiceListener.Failure) = endTypingWhatWasHeard(
            when (why) {
                VoiceListener.Failure.NoModel -> R.string.voice_no_model
                VoiceListener.Failure.NoMicrophone -> R.string.voice_no_microphone
            }
        )
    }

    /** The microphone opens now, the models load meanwhile. */
    fun begin() {
        service.watchVoice(::taken)
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

    // what composed was taken from the field by others: the app took it ([consumed]), not typed
    // again; else taken away, composed anew on the next heard
    private fun taken(consumed: Boolean) {
        if (over) return
        composing = false
        if (consumed) talk.consumed()
    }

    /** Stopped at [at] (uptime, ms: the lift's, the tap's): the tail after it counts from there, then the rest is typed. */
    fun finish(at: Long) {
        if (over || stopped) return
        stopped = true
        ui.recognizing()
        // the models not coming in, or a stretch not coming back: not waiting for good
        main.postDelayed({ endTypingWhatWasHeard(R.string.voice_hold_nothing) }, FINISH_MS)
        listener?.finish(at) { said ->
            val text = typed(talk.rest(said))
            if (text != null) {
                // it replaces what composes in the field: not taken away first, no flicker
                put(text)
                end(null)
            } else {
                // typed as it was said: not "nothing heard", though nothing is left
                end(if (talk.typedAny) null else R.string.voice_hold_nothing)
            }
        }
    }

    /**
     * The keyboard hidden, the field kept and its connection good: stopped as by a tap, what composes
     * taken away now, what is left typed straight into the field once recognized (not through fcitx,
     * which the hidden keyboard focused out).
     */
    fun hidden(at: Long) {
        if (over) return
        direct = true
        if (composing) service.composeVoice("")
        composing = false
        finish(at)
    }

    /** A press after the stop (the user's rule): the tail ends now, what was said is still typed. Recognizing already, it changes nothing. */
    fun cut() {
        if (!over && stopped) listener?.cut()
    }

    /** Dropped, whatever was heard and not typed yet: another field, the keyboard gone, the touch taken away, ×. */
    fun drop() = end(null)

    /**
     * What was heard so far typed now, the rest not waited for: the field is being left (another
     * field, the keyboard's view thrown away), its connection about to go, so not through fcitx's thread.
     */
    fun typeNow() {
        if (over) return
        typed(talk.restNow())?.let {
            composing = false
            service.commitVoice(it)
        }
        end(null)
    }

    // given up (15 s with nothing back) or failed: typed as it was said, what was heard last is
    // typed too, not dropped after what went in before it, and the message says it may be cut short
    private fun endTypingWhatWasHeard(@StringRes message: Int) {
        if (over) return
        if (!asItGoes || !talk.typedAny) {
            end(message)
            return
        }
        typed(talk.restNow())?.let(::put)
        end(if (message == R.string.voice_hold_nothing) R.string.voice_tap_cut_short else message)
    }

    // [text] into the field: as its own commit (through fcitx: the bar's suggestions after it), or
    // the keyboard hidden, straight in while the field's connection is still there
    private fun put(text: String) {
        composing = false
        if (!direct) {
            commit(text)
        } else if (service.currentInputConnection != null) {
            service.commitVoice(text)
        }
    }

    // [said] (what is left of it) to type, its end as the field has it; null if nothing, or not into this field
    private fun typed(said: String?): String? =
        VoiceCommit.typed(said?.let { ended(it) }.orEmpty(), sameField = sameField, passwordField = service.inPasswordField)

    // [text]'s end as the field it goes into has it, by the user's choice (语音输入 → 句末句号)
    private fun ended(text: String): String {
        val kind = field?.let { VoiceEnd.field(it.toEditorTraits(service.packageName), it.packageName) } ?: VoiceEnd.Field.OTHER
        return VoiceEnd.apply(text, kind, AppPrefs.getInstance().keyboard.voiceEndMark.getValue().mode)
    }

    private fun end(@StringRes message: Int?) {
        if (over) return
        over = true
        main.removeCallbacksAndMessages(null)
        service.watchVoice(null)
        // dropped, nothing heard, given up: what composed goes, in the field it composed in only
        if (composing && sameField) service.composeVoice("")
        composing = false
        listener?.close()
        listener = null
        ui.ended(message)
    }

    /** What [VoiceListener] is to a session: the microphone and the recognizer behind [VoiceListener.Events]. */
    interface Listening {
        fun load()
        fun start()

        /**
         * the microphone closed a tail after [stoppedAt] (uptime, ms), all of it recognized, then
         * [done] with what was said ([VoiceLive.finished], before the clean-up; null: nothing came back)
         */
        fun finish(stoppedAt: Long, done: (String?) -> Unit)

        /** the microphone closed now, what was heard still recognized */
        fun cut()
        fun close()
    }

    companion object {
        /**
         * The JVM's UAT tests' recognizer: what is heard told straight to the events, the chain
         * after the recognizer (VoiceHold, VoiceTap, VoiceTalk, VoiceEnd, VoiceCommit, the commit)
         * the app's own. sherpa-onnx and the microphone are a device's.
         */
        @VisibleForTesting
        internal var listening: ((VoiceListener.Events) -> Listening)? = null

        private const val FINISH_MS = 15_000L

        /** how long a message shows before the session's view goes */
        const val MESSAGE_MS = 1200L
    }
}
