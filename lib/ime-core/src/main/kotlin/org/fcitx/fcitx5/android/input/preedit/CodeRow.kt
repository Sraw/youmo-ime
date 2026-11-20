/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

/** One line: aux before the preedit, aux below after it, the cursor drawn as a character of its own. */
object CodeRow {

    /** In code points; [cursor] -1 for none, no character of the preedit; on the aux before, the start, after or past the end, the end. */
    fun tapAt(offset: Int, cursor: Int, aux: Int, preedit: String): Int {
        val at = ((if (cursor in 0 until offset) offset - 1 else offset) - aux).coerceIn(0, preedit.length)
        return preedit.codePointCount(0, at)
    }

    /** Null where [x] is in sight with [margin] already. */
    fun scrollFor(x: Int, margin: Int, scroll: Int, seen: Int): Int? = when {
        x - margin < scroll -> x - margin
        x + margin > scroll + seen -> x + margin - seen
        else -> null
    }?.coerceAtLeast(0)
}
