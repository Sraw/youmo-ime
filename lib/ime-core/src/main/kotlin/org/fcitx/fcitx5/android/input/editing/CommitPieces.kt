/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

/**
 * A long text cut into pieces the editor can be sent one by one ([EditingSession.commitText]).
 *
 * `commitText` reaches the editor as a one-way binder call, and one-way calls to a process share
 * half of its 1 MB binder buffer, room for some 250 000 characters: a clip of 297 000 pasted from
 * the clipboard panel (a parcel of 567 KB) failed (`FAILED BINDER TRANSACTION`) and nothing went
 * in, though the system's clipboard held it and the editor's own paste took it. Pieces of [MAX]
 * UTF-16 units go in one after another, the editor waited for between them. Cut between code
 * points, never inside a surrogate pair: a lone half is malformed text, which a WebView would
 * turn into U+FFFD.
 */
object CommitPieces {

    /**
     * UTF-16 units a piece holds at most: a parcel of 256 KB, half the room, one in flight at a
     * time; a text this long or shorter goes in one call, as it always did (200 000 characters went
     * whole). Each piece costs the editor a layout of its own, the later ones over more text.
     */
    const val MAX = 128 * 1024

    /**
     * [text] in order, each piece [max] units at most (one fewer where the cut would split a
     * surrogate pair); [text] alone if it fits.
     */
    fun split(text: String, max: Int = MAX): List<String> {
        require(max >= 2) { "a piece holds a surrogate pair at least" }
        if (text.length <= max) return listOf(text)
        val pieces = ArrayList<String>(text.length / max + 1)
        var from = 0
        while (from < text.length) {
            var to = minOf(from + max, text.length)
            if (to < text.length && Character.isHighSurrogate(text[to - 1]) && Character.isLowSurrogate(text[to])) to--
            pieces += text.substring(from, to)
            from = to
        }
        return pieces
    }
}
