/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.FormattedText

/**
 * An [OutputFilter] that rewrites a [FormattedText] as one string: chttrans's
 * (`ca/modules/chttrans/chttrans.cpp:133-183`). The segments are joined, [convert]ed as a whole (a
 * phrase can span two), then cut back into segments of the old segments' lengths in characters
 * (code points), each keeping its format flags; the last segments come out shorter, or empty, when
 * the result is shorter, and characters past the old total are dropped when it is longer, as in
 * fcitx. The cursor keeps its count of characters, capped at the new length; a cursor at or before
 * the start (0, -1) is kept as is.
 */
class WholeTextFilter(private val applies: (InputContext) -> Boolean, private val convert: (String) -> String) : OutputFilter {

    override fun filter(ic: InputContext, text: FormattedText): FormattedText {
        if (text.isEmpty() || !applies(ic)) return text
        val old = text.toString()
        val new = convert(old)
        val newLength = new.codePointCount(0, new.length)
        val strings: Array<String>
        if (text.strings.size == 1) {
            strings = arrayOf(new)
        } else {
            var off = 0
            var remain = newLength
            strings = Array(text.strings.size) { i ->
                val segment = text.strings[i]
                val length = minOf(remain, segment.codePointCount(0, segment.length))
                remain -= length
                val end = new.offsetByCodePoints(off, length)
                new.substring(off, end).also { off = end }
            }
        }
        val cursor = if (text.cursor > 0) {
            val chars = minOf(old.codePointCount(0, minOf(text.cursor, old.length)), newLength)
            strings.joinToString("").let { it.offsetByCodePoints(0, minOf(chars, it.codePointCount(0, it.length))) }
        } else {
            text.cursor
        }
        return FormattedText(strings, text.flags, cursor)
    }
}
