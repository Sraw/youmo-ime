/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * Shift on the nine keys' English, as the full keyboard's: pressed once, the next letter a
 * capital; pressed twice quickly or held, caps lock, until pressed again. On by itself where a
 * sentence starts ([AutoCaps]).
 */
class NineKeyShift {

    var caps: AutoCaps.Caps = AutoCaps.Caps.NONE
        private set

    private val autoCaps = AutoCaps()

    /** Whether the next letter is a capital. */
    val upper: Boolean get() = caps != AutoCaps.Caps.NONE

    /** Shift pressed, [lock] twice quickly or held: caps lock on, or off where it is. */
    fun pressed(lock: Boolean) {
        autoCaps.changed()
        caps = when {
            lock -> if (caps == AutoCaps.Caps.LOCK) AutoCaps.Caps.NONE else AutoCaps.Caps.LOCK
            caps == AutoCaps.Caps.NONE -> AutoCaps.Caps.ONCE
            else -> AutoCaps.Caps.NONE
        }
    }

    /** Where the editor [wants] a capital next or no longer: whether Shift changed. */
    fun auto(wants: Boolean): Boolean {
        val next = autoCaps.next(caps, wants)
        if (next == caps) return false
        caps = next
        return true
    }

    /** A letter typed: whether it is a capital, Shift pressed once used up by it. */
    fun typed(): Boolean {
        val upper = upper
        if (caps == AutoCaps.Caps.ONCE) {
            autoCaps.changed()
            caps = AutoCaps.Caps.NONE
        }
        return upper
    }

    /** Off, as the keys attach afresh. */
    fun reset() {
        autoCaps.changed()
        caps = AutoCaps.Caps.NONE
    }
}
