/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import java.util.concurrent.atomic.AtomicReference

/**
 * One hold of ⌫ (the keyboard's, or a hardware Backspace's auto-repeat): whether each of its repeats deletes.
 * A hold begun while something was typed (a preedit, a code, a stroke filter, the field's composing word)
 * stops once that is all deleted, for the rest of the hold: it never goes on into the field's text, which
 * takes letting go and pressing again (user 2026-11-15). One begun with nothing typed deletes on as ever.
 *
 * Told its press and asked each repeat in their turns on the host's thread, every key sent before them
 * handled: what the keyboard shows lags behind the engine, and a repeat 50 ms after the one that emptied
 * the preedit would else delete a character of the field's before the keyboard knew. The press's, not the
 * first repeat's: another finger's key may commit what is typed in the long press's wait between them.
 */
class BackspaceHold {
    // whether something was typed as the hold began: told by its press's turn on the host's thread, or by the
    // keyboard on the main thread where strokes written on the keys are there to take back; whichever tells first
    private val began = AtomicReference<Boolean?>(null)

    /** Set once the hold has stopped: its later repeats need not be sent. */
    @Volatile
    var stopped = false
        private set

    /** Its press's turn, [composing] as every key before it left things: what was typed as the hold began. */
    fun press(composing: Boolean) {
        began.compareAndSet(null, composing)
    }

    /** A repeat's turn, [composing] as every key before it left things: whether it goes on as a backspace. */
    fun repeat(composing: Boolean): Boolean {
        // a press never told (a repeat first in its turn): as things are now
        began.compareAndSet(null, composing)
        if (stopped || began.get() == true && !composing) {
            stopped = true
            return false
        }
        return true
    }

    /** Strokes written on the keys that the hold takes back, which the host does not see: something was typed as it began. */
    fun taken() {
        began.compareAndSet(null, true)
    }
}
