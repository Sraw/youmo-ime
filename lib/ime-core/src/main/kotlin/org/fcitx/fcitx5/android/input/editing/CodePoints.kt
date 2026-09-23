/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

/**
 * Converts between code points and UTF-16 units.
 *
 * Editors report positions in UTF-16 units, but deletions are asked for in code points so one
 * press removes a whole emoji. Anything that has to predict where such a deletion leaves the
 * cursor needs to know how many units those code points take up.
 */
object CodePoints {

    /**
     * The UTF-16 length of the last [count] code points of [text], or of all of [text] when it
     * holds fewer. A lone surrogate counts as one code point. Note that AOSP's
     * `deleteSurroundingTextInCodePoints` deletes nothing at all when it meets one, so a
     * prediction made from this is off in that (malformed-text) case; the cursor tracker
     * resynchronises when the editor reports back.
     */
    fun lengthOfLast(text: CharSequence, count: Int): Int {
        var i = text.length
        var n = 0
        while (i > 0 && n < count) {
            i -= if (i >= 2 && Character.isLowSurrogate(text[i - 1]) &&
                Character.isHighSurrogate(text[i - 2])
            ) 2 else 1
            n++
        }
        return text.length - i
    }

    /** The UTF-16 length of the first [count] code points of [text]; see [lengthOfLast]. */
    fun lengthOfFirst(text: CharSequence, count: Int): Int {
        var i = 0
        var n = 0
        while (i < text.length && n < count) {
            i += if (i + 1 < text.length && Character.isHighSurrogate(text[i]) &&
                Character.isLowSurrogate(text[i + 1])
            ) 2 else 1
            n++
        }
        return i
    }
}
