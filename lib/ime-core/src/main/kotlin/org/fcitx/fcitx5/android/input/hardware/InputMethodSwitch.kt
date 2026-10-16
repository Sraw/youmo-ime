/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.hardware

import org.fcitx.fcitx5.android.engine.host.Engines

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

    /**
     * Whether [toggle] has somewhere to go, answered before fcitx is asked: the Chinese one it came
     * from is no matter, the first in the list goes in its stead.
     */
    fun canToggle(current: String, enabled: List<String>, english: String, usable: (String) -> Boolean): Boolean =
        toggle(current, enabled, null, english, usable) != null

    /** Ctrl+Shift: the next one after [current] in the list, round to the start; from one not in it, the first. */
    fun next(current: String, enabled: List<String>, usable: (String) -> Boolean): String? {
        val at = enabled.indexOf(current)
        // round to [current] itself, which is skipped: from one not in the list (-1) the last is reached too
        return (1..enabled.size).asSequence()
            .map { enabled[(at + it).mod(enabled.size)] }
            .firstOrNull { it != current && usable(it) }
    }

    /**
     * A key of a hardware keyboard pressed in [current], made for a touch screen ([takesKeyboard]
     * false, as handwriting): the one that types it, pinyin as Sogou and Baidu have it (全拼 or 双拼,
     * whichever comes first in the list), else the first Chinese one in the list that takes a
     * keyboard, else English. Null: [current] types it, as it takes a keyboard or none in the list does.
     */
    fun forKeyboard(current: String, enabled: List<String>, english: String, takesKeyboard: (String) -> Boolean): String? =
        if (takesKeyboard(current)) {
            null
        } else {
            enabled.firstOrNull { it in PINYINS && takesKeyboard(it) }
                ?: enabled.firstOrNull { it != english && takesKeyboard(it) }
                ?: english.takeIf { it in enabled }
        }

    private val PINYINS = setOf(Engines.PINYIN, Engines.SHUANGPIN)
}
