/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.input.FcitxInputMethodService

/** Main thread. */
class VoiceHoldSession(
    service: FcitxInputMethodService,
    fcitx: HostConnection,
    private val hold: VoiceHold,
    private val ui: Ui,
    commit: (String) -> Unit,
) {
    interface Ui : VoiceSession.Ui {
        fun zone(cancel: Boolean)
    }

    private val session = VoiceSession(service, fcitx, ui, asItGoes = false, commit)

    val over get() = session.over

    /** input restarted in it */
    val sameField get() = session.sameField

    /** the finger has nothing more to say */
    val released get() = hold.released

    fun begin() = session.begin()

    fun moved(dy: Float) {
        if (!session.over && hold.moved(dy)) ui.zone(hold.zone == VoiceHold.Zone.Cancel)
    }

    /** Uptime ms: the tail after it counts from there. */
    fun lift(at: Long) {
        if (session.over || hold.released) return
        if (hold.release()) session.finish(at) else session.drop()
    }

    /** The tail ends now, what was said is still typed, the press itself counts for nothing. */
    fun pressedAgain() {
        if (hold.released) session.cut()
    }

    fun hidden(at: Long) = session.hidden(at)

    /** The touch taken away. */
    fun drop() = session.drop()

    /** What was heard typed into it now, as a tap's talk does; in the cancel zone, dropped. */
    fun fieldLeft() {
        if (!hold.released && hold.zone == VoiceHold.Zone.Cancel) session.drop() else session.typeNow()
    }
}
