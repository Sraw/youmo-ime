/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import java.text.BreakIterator

/**
 * A long text cut into pieces the editor can be sent one by one ([EditingSession.commitText]).
 *
 * `commitText` reaches the editor as a one-way binder call, and one-way calls to a process share
 * half of its 1 MB binder buffer, room for some 250 000 characters: a clip of 297 000 pasted from
 * the clipboard panel (a parcel of 567 KB) failed (`FAILED BINDER TRANSACTION`) and nothing went
 * in, though the system's clipboard held it and the editor's own paste took it. Pieces of [MAX]
 * UTF-16 units go in one after another. Cut between characters (grapheme clusters): never inside
 * a surrogate pair, whose lone half a WebView would turn into U+FFFD, nor a CRLF, a letter and its
 * combining marks, an emoji's ZWJ sequence.
 *
 * A piece's buffer is held from its sending till the editor's binder thread has taken it, a few
 * milliseconds, not till its main thread has laid it out: pieces sent back to back overflow it (450 000
 * characters as 14 pieces lost 3 in the middle) and a piece [GAP_MS] after the last does not. One
 * that fails all the same is sent again after a pause; the keyboard's main thread pauses
 * [RETRY_BUDGET_MS] in all at most, then gives up on the rest (an editor that never takes them).
 * Waiting for the editor to answer a read instead held the keyboard as long as the editor took to
 * lay each piece out, up to 2 s a read: 450 000 characters, 1.1 to 2 s on the emulator.
 */
object CommitPieces {

    /**
     * UTF-16 units a piece holds at most: a parcel of 256 KB, half the room; a text this long or
     * shorter goes in one call, as it always did (200 000 characters went whole). Each piece costs
     * the editor a layout of its own, the later ones over more text.
     */
    const val MAX = 128 * 1024

    /** Milliseconds between two pieces: the editor's binder thread takes the last meanwhile. */
    const val GAP_MS = 16L

    /** Milliseconds the pauses after failed pieces may add up to, in one commit. */
    const val RETRY_BUDGET_MS = 1000L

    /** The first pause after a failed piece, doubled each failure up to [LAST_RETRY_MS]. */
    const val FIRST_RETRY_MS = 8L
    const val LAST_RETRY_MS = 128L

    /**
     * [text] in order, each piece [max] units at most, cut where a character ends (where a code
     * point ends, if one character is longer than [max]); [text] alone if it fits.
     */
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
