/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

/** Editors report UTF-16 positions but deletions go in code points (a whole emoji a press): predicting the cursor needs the units. */
object CodePoints {

    /** Or all of [text] when it holds fewer; null with an unpaired surrogate, which AOSP's `deleteSurroundingTextInCodePoints` refuses. */
    fun lengthOfLast(text: CharSequence, count: Int): Int? {
        var i = text.length
        var n = 0
        while (i > 0 && n < count) {
            val c = text[i - 1]
            i -= when {
                Character.isLowSurrogate(c) ->
                    if (i >= 2 && Character.isHighSurrogate(text[i - 2])) 2 else return null
                Character.isHighSurrogate(c) -> return null
                else -> 1
            }
            n++
        }
        return text.length - i
    }

    /** See [lengthOfLast]. */
    fun lengthOfFirst(text: CharSequence, count: Int): Int? {
        var i = 0
        var n = 0
        while (i < text.length && n < count) {
            val c = text[i]
            i += when {
                Character.isHighSurrogate(c) ->
                    if (i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) 2 else return null
                Character.isLowSurrogate(c) -> return null
                else -> 1
            }
            n++
        }
        return i
    }
}
