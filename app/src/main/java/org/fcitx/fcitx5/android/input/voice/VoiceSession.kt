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
import org.fcitx.fcitx5.android.input.editing.EditingSession
import org.fcitx.fcitx5.android.input.editing.toEditorTraits
import java.util.EnumSet

/**
 * One talk, however started (space held, [VoiceHoldSession]; bar microphone tapped, [VoiceTapOverlay]):
 * what is heard composes in the field, and what was said is [commit]ted once it [finish]es, into the
 * field it was said for and never a password. [asItGoes]: the settled part is typed as said, only the
 * tail composes. Used from the main thread.
 *
 * Others may take what composes: the app sending or clearing it (not typed again), or the cursor
 * moved or input restarted (taken away; composes anew where the cursor is).
 */
class VoiceSession(
    private val service: FcitxInputMethodService,
    private val fcitx: HostConnection,
    private val ui: Ui,
    asItGoes: Boolean,
    private val commit: (String) -> Unit,
) {
    interface Ui {
        /** its first sample not zero: 正在听 and a short vibration, 准备中 before */
        fun ready()

        /** 2 s of zeroes: 麦克风没有声音 until [ready] */
        fun silent()

        fun heard(text: String)

        fun level(level: Float)

        fun recognizing()

        /** [message] null: typed or dropped; else shown a moment. */
        fun ended(@StringRes message: Int?)
    }

    // the same one after input restarts in it (another EditorInfo, the same app and view id)
    private val field = service.currentInputEditorInfo
    private val main = Handler(Looper.getMainLooper())
    private var listener: Listening? = null

    /** Not in a password, nor over a word the field composes already (English's); there only the session's view shows it. */
    val composes = service.voiceComposes

    // typing as it is said needs the field's composing text: not over fcitx's
    private val asItGoes = asItGoes && composes
    private val talk = VoiceTalk(this.asItGoes)

    // what was heard before the last of them is not this one's
    private var utterance = 0

    // null: none since the app took the last
    private var shownUpTo: VoiceLive.Mark? = null

    // ours: taken away if nothing is typed
    private var composing = false

    // nothing composes, what is left goes straight into the field
    private var direct = false

    var over = false
        private set

    var stopped = false
        private set

    val sameField get() = service.sameField(field)

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
            // heard before the app took what was said: gone with it
            if (over || heard.utterance < utterance) return
            shownUpTo = heard.upTo
            // the engine composes now (another finger typing): nothing typed meanwhile, nor its text made voice's
            val inField = composes && !direct && sameField && service.voiceOwnsComposing
            val step = talk.heard(heard, typing = inField)
            ui.heard(heard.text)
            if (inField) {
                // in place of what composed: then the tail composes after it
                service.voiceHeard(step.type, step.composing)
                composing = step.composing.isNotEmpty()
            }
        }

        override fun level(level: Float) = ui.level(level)

        override fun failed(why: VoiceListener.Failure) = endTypingWhatWasHeard(
            when (why) {
                VoiceListener.Failure.NoModel -> R.string.voice_no_model
                VoiceListener.Failure.NoMicrophone -> R.string.voice_no_microphone
                VoiceListener.Failure.Recognition -> R.string.voice_recognition_failed
            }
        )
    }

    /** The models load meanwhile. */
    fun begin() {
        service.watchVoice(::taken)
        service.voiceTalkBegan()
        listener = (listening?.invoke(events) ?: VoiceListener(
            events,
            hotwords = { VoiceEngine.userHotwords(fcitx, habits = AppPrefs.getInstance().internal.useHabits.getValue()) },
            language = VoiceEngine.language(fcitx),
        )).also {
            it.load()
            it.start()
        }
    }

    // the app took it (sent, cleared): what is said next is a new utterance, nothing joined or typed again;
    // left as text, counted as typed. Else taken away, composed anew on the next heard
    private fun taken(how: EditingSession.Taken) {
        if (over) return
        composing = false
        when (how) {
            EditingSession.Taken.AWAY -> Unit
            EditingSession.Taken.LEFT -> talk.kept()
            EditingSession.Taken.GONE -> {
                talk.consumed()
                listener?.restart(++utterance, shownUpTo)
                shownUpTo = null
            }
        }
    }

    /** [at]: uptime ms of the lift or tap; the tail counts from there. */
    fun finish(at: Long) {
        if (over || stopped) return
        stopped = true
        ui.recognizing()
        // the models not coming in, or a stretch not coming back: not waiting for good
        main.postDelayed({ endTypingWhatWasHeard(R.string.voice_hold_nothing) }, FINISH_MS)
        listener?.finish(at) { heard, raw ->
            // all of it was said before the app took it: nothing of it left to type
            val current = listener?.let { it.utterance >= utterance } ?: true
            val said = heard.takeIf { current }
            val text = typed(talk.rest(said, raw))
            if (text != null) {
                // it replaces what composes in the field: not taken away first, no flicker
                put(text)
                end(polishHint())
            } else {
                // typed as it was said: not "nothing heard", though nothing is left
                end(if (talk.typedAny) polishHint() else R.string.voice_hold_nothing)
            }
        }
    }

    /** Typed straight into the field once recognized, not through fcitx, which the hidden keyboard focused out. */
    fun hidden(at: Long) {
        if (over) return
        direct = true
        if (composing) service.composeVoice("")
        composing = false
        finish(at)
    }

    /** A press after the stop: the tail ends now, what was said is still typed. */
    fun cut() {
        if (!over && stopped) listener?.cut()
    }

    /** Another field, the keyboard gone, the touch taken away. */
    fun drop() = end(null)

    /** The field is being left and its connection is about to go, so not through fcitx's thread. */
    fun typeNow() {
        if (over) return
        typed(talk.restNow())?.let {
            composing = false
            service.commitVoice(it)
        }
        end(null)
    }

    // given up (15 s with nothing back) or failed: what was heard is typed; "nothing heard" only if
    // nothing went in, else that the end may be cut short
    private fun endTypingWhatWasHeard(@StringRes message: Int) {
        if (over) return
        val put = typed(talk.restNow())?.let(::put) == true
        val some = talk.typedAny || put
        end(if (some && message == R.string.voice_hold_nothing) R.string.voice_tap_cut_short else message)
    }

    // through fcitx (the bar's suggestions after it), or with the keyboard hidden straight in. False if
    // it could not go in
    private fun put(text: String): Boolean {
        composing = false
        if (!direct) {
            commit(text)
        } else if (service.currentInputConnection != null) {
            service.commitVoice(text)
        } else {
            return false
        }
        return true
    }

    // null if nothing, or not into this field
    private fun typed(said: String?): String? =
        VoiceCommit.typed(said?.let { ended(it) }.orEmpty(), sameField = sameField, passwordField = service.inPasswordField)

    // by the user's choice (语音输入 → 句末句号)
    private fun ended(text: String): String {
        val kind = field?.let { VoiceEnd.field(it.toEditorTraits(service.packageName), it.packageName) } ?: VoiceEnd.Field.OTHER
        return VoiceEnd.apply(text, kind, AppPrefs.getInstance().keyboard.voiceEndMark.getValue().mode)
    }

    // said once per cause while the process lives; the failure itself is logged where it happened
    @StringRes
    private fun polishHint(): Int? {
        // a hint on a hidden card would go unseen: kept for one that shows
        if (direct) return null
        val why = listener?.polishFailure ?: return null
        return if (polishHinted.add(why)) R.string.voice_polish_failed else null
    }

    private fun end(@StringRes message: Int?) {
        if (over) return
        over = true
        main.removeCallbacksAndMessages(null)
        service.watchVoice(null)
        // followed for 语音先问再记
        service.voiceTalkEnded()
        // what composed goes, in the field it composed in only
        if (composing && sameField) service.composeVoice("")
        composing = false
        listener?.close()
        listener = null
        ui.ended(message)
    }

    /** LOAD: its model not read in; RUN: failing on this talk. */
    enum class PolishFailure { LOAD, RUN }

    interface Listening {
        fun load()
        fun start()

        /** [done] gets [VoiceLive.finished] before the clean-up (null: nothing came back); raw: the polish failed */
        fun finish(stoppedAt: Long, done: (said: String?, raw: Boolean) -> Unit)

        /** read once [finish] is done */
        val polishFailure: PolishFailure? get() = null

        /** what was said up to [upTo] (null: none of this utterance) was taken by the app */
        fun restart(to: Int, upTo: VoiceLive.Mark?)

        val utterance: Int

        /** what was heard is still recognized */
        fun cut()
        fun close()
    }

    companion object {
        /** The JVM UAT tests' recognizer: the chain after it is the app's own; sherpa-onnx and the microphone are a device's. */
        @VisibleForTesting
        internal var listening: ((VoiceListener.Events) -> Listening)? = null

        // main thread
        @VisibleForTesting
        internal val polishHinted: MutableSet<PolishFailure> = EnumSet.noneOf(PolishFailure::class.java)

        private const val FINISH_MS = 15_000L

        const val MESSAGE_MS = 1200L
    }
}
