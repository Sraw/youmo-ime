/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import java.util.concurrent.atomic.AtomicReference

/**
 * One hold of ⌫: a hold begun with something typed stops once that is all deleted, never going on into the field's
 * text. Asked in turn on the host's thread after every key sent before: the keyboard's view lags the engine, and a
 * repeat right after emptying the preedit would else delete a field character. The press decides, not the first repeat:
 * another finger may commit during the long-press wait.
 */
class BackspaceHold {
    // whatever tells first: the press's turn on the host thread, or the keyboard where strokes on the keys are to take back
    private val began = AtomicReference<Boolean?>(null)

    /** Its later repeats need not be sent. */
    @Volatile
    var stopped = false
        private set

    /** [composing] as every key before it left things. */
    fun press(composing: Boolean) {
        began.compareAndSet(null, composing)
    }

    /** Whether it goes on as a backspace. */
    fun repeat(composing: Boolean): Boolean {
        // a press never told (a repeat first in its turn): as things are now
        began.compareAndSet(null, composing)
        if (stopped || began.get() == true && !composing) {
            stopped = true
            return false
        }
        return true
    }

    /** The host does not see strokes on the keys: something was typed as it began. */
    fun taken() {
        began.compareAndSet(null, true)
    }
}
