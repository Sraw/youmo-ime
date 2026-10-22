/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

/**
 * Whether typing went on since the undo chip came (review-E-ui S3): typing hides it, where an
 * act's own list shown anew (the same preedit again) keeps it. Told what is being typed, as the
 * editor's and the bar's preedit say, and of a key let through or a word put in.
 */
class ChipTyping {

    private var editor = ""
    private var bar = ""

    // the preedit when the chip came; null once a key or a word went in since
    private var atChip: Pair<String, String>? = editor to bar

    /** The chip shown now. */
    fun chipShown() {
        atChip = editor to bar
    }

    /** The editor's preedit is [text]: whether the chip goes. */
    fun editorPreedit(text: String): Boolean {
        editor = text
        return changed()
    }

    /** The bar's preedit is [text]: whether the chip goes. */
    fun barPreedit(text: String): Boolean {
        bar = text
        return changed()
    }

    /** A key the engine let through, or a word put in: the chip goes. */
    fun typed(): Boolean {
        atChip = null
        return true
    }

    private fun changed() = atChip != editor to bar
}
