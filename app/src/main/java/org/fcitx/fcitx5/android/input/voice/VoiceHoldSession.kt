/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.input.FcitxInputMethodService

/**
 * One hold to talk on the space key: a [VoiceSession] from the press, [VoiceHold] deciding what
 * the finger does, what was said [commit]ted once it lifts, all of it at the end. Used from the
 * main thread.
 */
class VoiceHoldSession(
    service: FcitxInputMethodService,
    fcitx: HostConnection,
    /** VoiceHold's: how far the finger goes to drop it */
    private val hold: VoiceHold,
    private val ui: Ui,
    commit: (String) -> Unit,
) {
    interface Ui : VoiceSession.Ui {
        /** slid into the zone where lifting drops it, or back out */
        fun zone(cancel: Boolean)
    }

    private val session = VoiceSession(service, fcitx, ui, asItGoes = false, commit)

    val over get() = session.over

    /** the field the talk started in still the one being typed into (input restarted in it) */
    val sameField get() = session.sameField

    /** lifted, the rest the recognizer's: the finger has nothing more to say */
    val released get() = hold.released

    /** The press: the microphone opens now, the models load meanwhile. */
    fun begin() = session.begin()

    /** The finger moved: as [VoiceHold.moved]. */
    fun moved(dy: Float) {
        if (!session.over && hold.moved(dy)) ui.zone(hold.zone == VoiceHold.Zone.Cancel)
    }

    /** The finger up at [at] (its event's uptime, ms): the tail after it counts from there. */
    fun lift(at: Long) {
        if (session.over || hold.released) return
        if (hold.release()) session.finish(at) else session.drop()
    }

    /**
     * A new press after the lift (the user's rule): the tail ends now, what was said is still
     * typed, the press itself counts for nothing. Recognizing already, it changes nothing.
     */
    fun pressedAgain() {
        if (hold.released) session.cut()
    }

    /**
     * The keyboard hidden after the lift: as a tap's talk, the recognizer waited for and the rest,
     * polished, typed straight into the field ([VoiceSession.hidden]).
     */
    fun hidden(at: Long) = session.hidden(at)

    /** Dropped, whatever was heard: the touch taken away. */
    fun drop() = session.drop()

    /**
     * The field left before the talk ended (another field, the keyboard hidden or thrown away): what
     * was heard typed into it now, as a tap's talk does (the owner's, 060-pending-decisions L490); slid
     * into the cancel zone, dropped, as lifting there would.
     */
    fun fieldLeft() {
        if (!hold.released && hold.zone == VoiceHold.Zone.Cancel) session.drop() else session.typeNow()
    }
}
