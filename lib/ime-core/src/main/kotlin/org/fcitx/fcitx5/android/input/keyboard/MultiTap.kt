/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * 连按 on the nine keys: a key pressed once is any of its letters, for the engine to guess; the
 * same key pressed again within [timeout] ms of the last press is its second letter, then its
 * third, round to the first again (`4` 4 is h, 4 4 4 is i, a fourth g), and nothing else: the
 * engine takes it as a letter typed as itself, what the key's preview showed (2026-10-19; read as
 * keys too, a learned 广告 overrode the h). A [timeout] of 0 is off, the default: every press is a
 * key of its own.
 *
 * Times are the presses' own (the touch's), not when they reach the engine, which may be busy
 * reading the last.
 */
class MultiTap(private val timeout: Long) {

    private var key = NONE
    private var count = 0
    private var at = 0L

    /**
     * What the run's last press committed, set by whoever commits it: the next press of the run
     * takes back this, not the last letter mapped anew (1's marks map differently once the first
     * committed: 半角 after a digit, then the context reset). Empty when nothing of a run is committed: cleared
     * as a run starts, so a run whose first press committed nothing takes nothing back.
     */
    var typed: String = ""

    /**
     * The punctuation's map the run's first press typed with, set by whoever commits it: the run's next presses
     * type with it too, not with the map the engine tells after the first committed (半角 after a digit: the , typed
     * is no digit, so its map back to full width would make 1's run , then 。). Empty as a run starts.
     */
    var marks: Map<String, String> = emptyMap()

    /**
     * What pressing [key], whose letters are [letters], at [time] types: null for the key, any of
     * its letters; else the letter that takes the place of the last press.
     */
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

    /** What [press] would type at [time], nothing noted: for the key's preview. */
    fun peek(key: Char, letters: String, time: Long): Char? = letter(letters, countAt(key, time))

    /** Something other than a key pressed: the next press is a key of its own. */
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
