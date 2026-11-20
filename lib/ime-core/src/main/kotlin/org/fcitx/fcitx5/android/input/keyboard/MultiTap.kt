/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * 连按 on the nine keys: a key pressed again within [timeout] ms is its next letter, round again (4 4 is h); the
 * engine takes it as typed, not as keys (a learned 广告 overrode the h). [timeout] 0 is off. Times are the touches'.
 */
class MultiTap(private val timeout: Long) {

    private var key = NONE
    private var count = 0
    private var at = 0L

    /** Set by whoever commits it: the next press takes back this, not the letter mapped anew (1's marks remap after a commit). */
    var typed: String = ""

    /** The map the run's first press typed with, kept for the run (半角 after a digit would make 1's run , then 。). */
    var marks: Map<String, String> = emptyMap()

    /** Null: the key, any of its letters; else the letter replacing the last press. */
    fun press(key: Char, letters: String, time: Long): Char? {
        count = countAt(key, time)
        if (count <= 1) {
            typed = ""
            marks = emptyMap()
        }
        this.key = key
        at = time
        return letter(letters, count)
    }

    /** Nothing noted: for the key's preview. */
    fun peek(key: Char, letters: String, time: Long): Char? = letter(letters, countAt(key, time))

    /** The next press is a key of its own. */
    fun reset() {
        key = NONE
        count = 0
        typed = ""
        marks = emptyMap()
    }

    private fun countAt(key: Char, time: Long): Int =
        if (timeout > 0 && key == this.key && time - at in 0..timeout) count + 1 else 1

    private fun letter(letters: String, count: Int): Char? =
        if (count <= 1 || letters.isEmpty()) null else letters[(count - 1) % letters.length]

    private companion object {
        const val NONE = '\u0000'
    }
}
