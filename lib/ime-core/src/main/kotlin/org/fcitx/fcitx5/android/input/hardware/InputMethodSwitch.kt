/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.hardware

/** Of the user's [enabled] ones in their order. */
object InputMethodSwitch {

    /** Chinese to English, English back to [last] or the first. Null: nowhere to go. */
    fun toggle(current: String, enabled: List<String>, last: String?, english: String): String? =
        if (current != english) {
            english.takeIf { it in enabled }
        } else {
            last?.takeIf { it in enabled } ?: enabled.firstOrNull { it != english }
        }

    /** Asked before fcitx: with no [last], the first in the list goes instead. */
    fun canToggle(current: String, enabled: List<String>, english: String): Boolean =
        toggle(current, enabled, null, english) != null

    /** Round to the start; from one not in it, the first. */
    fun next(current: String, enabled: List<String>): String? {
        val at = enabled.indexOf(current)
        // round to [current] itself, which is skipped: from one not in the list (-1) the last is reached too
        return (1..enabled.size).asSequence()
            .map { enabled[(at + it).mod(enabled.size)] }
            .firstOrNull { it != current }
    }
}
