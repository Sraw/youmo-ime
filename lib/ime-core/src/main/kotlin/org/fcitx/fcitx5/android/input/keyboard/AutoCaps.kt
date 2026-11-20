/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/** Shift-once on at a sentence start, off where the cursor no longer is at one; the user's Shift or caps lock left alone. */
class AutoCaps {

    enum class Caps { NONE, ONCE, LOCK }

    // Shift-once is on because of a sentence start, not the user
    private var turnedOn = false

    /** [wants]: `getCursorCapsMode` says so and the user has auto caps on. */
    fun next(current: Caps, wants: Boolean): Caps = when {
        current == Caps.NONE && wants -> Caps.ONCE.also { turnedOn = true }
        current == Caps.ONCE && turnedOn && !wants -> Caps.NONE.also { turnedOn = false }
        else -> current
    }

    /** A letter used it up, or the user pressed it. */
    fun changed() {
        turnedOn = false
    }
}
