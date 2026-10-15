/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.hardware

/**
 * Which input method a hardware keyboard's shortcut, or key, goes to, of the user's [enabled] ones
 * in their order. [english] is English's; [usable] says whether one may be gone to: while the
 * hardware keyboard is typed on, only those it can type (Engines.takesKeyboard).
 */
object InputMethodSwitch {

    /**
     * Shift or Ctrl+Space: from a Chinese input method to English, from English back to the Chinese
     * one it came from, [last], or failing that the first in the list. Null: nowhere to go.
     */
    fun toggle(current: String, enabled: List<String>, last: String?, english: String, usable: (String) -> Boolean): String? =
        if (current != english) {
            english.takeIf { it in enabled }
        } else {
            last?.takeIf { it in enabled && usable(it) } ?: enabled.firstOrNull { it != english && usable(it) }
        }

    /** Ctrl+Shift: the next one after [current] in the list, round to the start. */
    fun next(current: String, enabled: List<String>, usable: (String) -> Boolean): String? {
        val at = enabled.indexOf(current)
        return (1 until enabled.size).asSequence()
            .map { enabled[(at + it).mod(enabled.size)] }
            .firstOrNull { it != current && usable(it) }
    }

    /**
     * A key of a hardware keyboard pressed in [current], made for a touch screen ([takesKeyboard]
     * false, as handwriting): the one that types it, the first Chinese one in the list that takes a
     * keyboard, else English. Null: [current] types it.
     */
    fun forKeyboard(current: String, enabled: List<String>, english: String, takesKeyboard: (String) -> Boolean): String? =
        if (takesKeyboard(current)) {
            null
        } else {
            enabled.firstOrNull { it != english && takesKeyboard(it) } ?: english.takeIf { it in enabled }
        }
}
