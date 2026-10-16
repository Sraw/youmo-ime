/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

/**
 * The row above the candidates where the code being typed shows (the app's CodeRowUi): one line,
 * aux before the preedit and aux below after it, the cursor drawn as a character of its own.
 */
object CodeRow {

    /**
     * Where a tap at [offset] into the line shown puts the cursor in [preedit], in code points
     * (FcitxAPI.moveCursor): the preedit after [aux] characters of aux, the cursor drawn at [cursor]
     * of the line (-1 for none), which is no character of the preedit. A tap on the aux before it is
     * the preedit's start; on the aux after it, or past the end, its end.
     */
    fun tapAt(offset: Int, cursor: Int, aux: Int, preedit: String): Int {
        val at = ((if (cursor in 0 until offset) offset - 1 else offset) - aux).coerceIn(0, preedit.length)
        return preedit.codePointCount(0, at)
    }

    /**
     * Where the row, scrolled to [scroll] and [seen] wide, scrolls for [x] to be in sight with [margin]
     * of the text around it, or null where it is.
     */
    fun scrollFor(x: Int, margin: Int, scroll: Int, seen: Int): Int? = when {
        x - margin < scroll -> x - margin
        x + margin > scroll + seen -> x + margin - seen
        else -> null
    }?.coerceAtLeast(0)
}
