/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.hardware

/** A hardware key the IME took (Shift+Delete forgetting a candidate): its repeats and release go no further; a repeat never takes; one key at a time. */
class TakenKey {

    private var taken = NONE

    /** Whether it goes no further. */
    fun down(key: Int, repeat: Boolean, take: () -> Boolean): Boolean {
        if (repeat) return key == taken
        // another key meanwhile leaves this one taken till its release: let go, its repeats would reach fcitx and delete
        val took = take()
        if (took) taken = key else if (key == taken) taken = NONE
        return took
    }

    /** Whether its release goes no further. */
    fun up(key: Int): Boolean {
        if (key != taken) return false
        taken = NONE
        return true
    }

    private companion object {
        const val NONE = Int.MIN_VALUE
    }
}
