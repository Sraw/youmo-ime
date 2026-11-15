/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.hardware

/**
 * Which input method a hardware keyboard's shortcut, or key, goes to, of the user's [enabled] ones
 * in their order. [english] is English's.
 */
object InputMethodSwitch {

    /**
     * Shift or Ctrl+Space: from a Chinese input method to English, from English back to the Chinese
     * one it came from, [last], or failing that the first in the list. Null: nowhere to go.
     */
    fun toggle(current: String, enabled: List<String>, last: String?, english: String): String? =
        if (current != english) {
            english.takeIf { it in enabled }
        } else {
            last?.takeIf { it in enabled } ?: enabled.firstOrNull { it != english }
        }

    /**
     * Whether [toggle] has somewhere to go, answered before fcitx is asked: the Chinese one it came
     * from is no matter, the first in the list goes in its stead.
     */
    fun canToggle(current: String, enabled: List<String>, english: String): Boolean =
        toggle(current, enabled, null, english) != null

    /** Ctrl+Shift: the next one after [current] in the list, round to the start; from one not in it, the first. */
    fun next(current: String, enabled: List<String>): String? {
        val at = enabled.indexOf(current)
        // round to [current] itself, which is skipped: from one not in the list (-1) the last is reached too
        return (1..enabled.size).asSequence()
            .map { enabled[(at + it).mod(enabled.size)] }
            .firstOrNull { it != current }
    }
}
