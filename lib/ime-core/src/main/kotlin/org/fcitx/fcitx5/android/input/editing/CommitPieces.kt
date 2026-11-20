/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import java.text.BreakIterator

/**
 * A long text cut for [EditingSession.commitText]: one-way binder calls share half the editor process's 1 MB buffer,
 * so a 297 000-character paste failed whole. Pieces of [MAX] units, cut between grapheme clusters (a WebView turns a
 * lone surrogate half into U+FFFD). Sent back to back they overflow the buffer; [GAP_MS] apart they do not. A failed
 * piece is retried after a pause, at most [RETRY_BUDGET_MS] in all. Waiting on a read instead held the keyboard up to 2 s.
 */
object CommitPieces {

    /** Half the buffer; a text this long or shorter goes in one call, as it always did. */
    const val MAX = 128 * 1024

    /** The editor's binder thread takes the last meanwhile. */
    const val GAP_MS = 16L

    /** In one commit. */
    const val RETRY_BUDGET_MS = 1000L

    /** Doubled each failure up to [LAST_RETRY_MS]. */
    const val FIRST_RETRY_MS = 8L
    const val LAST_RETRY_MS = 128L

    /** Cut where a character ends (a code point, if one character is longer than [max]); [text] alone if it fits. */
    fun split(text: String, max: Int = MAX): List<String> {
        require(max >= 2) { "a piece holds a surrogate pair at least" }
        if (text.length <= max) return listOf(text)
        val characters = BreakIterator.getCharacterInstance().apply { setText(text) }
        val pieces = ArrayList<String>(text.length / max + 1)
        var from = 0
        while (from < text.length) {
            var to = minOf(from + max, text.length)
            if (to < text.length && !characters.isBoundary(to)) {
                val before = characters.preceding(to)
                to = if (before > from) {
                    before
                } else if (Character.isHighSurrogate(text[to - 1]) && Character.isLowSurrogate(text[to])) {
                    to - 1
                } else {
                    to
                }
            }
            pieces += text.substring(from, to)
            from = to
        }
        return pieces
    }
}
