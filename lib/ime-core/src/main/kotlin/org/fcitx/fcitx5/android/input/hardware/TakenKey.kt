/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.hardware

/**
 * A hardware key whose press the input method took for itself (Shift+Delete forgetting the
 * candidate highlighted, U-82): its repeats while held and its release go no further either, not
 * to fcitx as the key alone. A key whose first press was not taken is never taken by a repeat:
 * held down, Shift+Delete forgets once. One key is taken at a time: another taken while it is held
 * is the one from then on.
 */
class TakenKey {

    private var taken = NONE

    /** [key] pressed, [repeat] if held down; [take] says whether this press is taken. Whether it goes no further. */
    fun down(key: Int, repeat: Boolean, take: () -> Boolean): Boolean {
        if (repeat) return key == taken
        // another key pressed meanwhile (Shift again) leaves the taken one taken until its own
        // release: let go of here, its later repeats would reach fcitx as the key alone and delete
        val took = take()
        if (took) taken = key else if (key == taken) taken = NONE
        return took
    }

    /** [key] let go: whether its release goes no further. */
    fun up(key: Int): Boolean {
        if (key != taken) return false
        taken = NONE
        return true
    }

    private companion object {
        const val NONE = Int.MIN_VALUE
    }
}
