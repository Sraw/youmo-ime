/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

/** Whether typing went on since the undo chip came: typing hides it, an act's own list shown anew (same preedit) keeps it. */
class ChipTyping {

    private var editor = ""
    private var bar = ""

    // null once a key or a word went in since
    private var atChip: Pair<String, String>? = editor to bar

    fun chipShown() {
        atChip = editor to bar
    }

    /** Whether the chip goes. */
    fun editorPreedit(text: String): Boolean {
        editor = text
        return changed()
    }

    /** Whether the chip goes. */
    fun barPreedit(text: String): Boolean {
        bar = text
        return changed()
    }

    fun typed(): Boolean {
        atChip = null
        return true
    }

    /** Not on a release: a shortcut that raised a tip (Ctrl+.) comes back up unaccepted and would hide it at once. */
    fun key(up: Boolean): Boolean = !up && typed()

    private fun changed() = atChip != editor to bar
}
