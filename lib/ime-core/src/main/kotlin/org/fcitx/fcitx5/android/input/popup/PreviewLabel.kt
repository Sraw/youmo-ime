/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import java.text.BreakIterator

/**
 * A swipe shortcut's name or text in the key's preview bubble: words smaller than the letter's size and at most
 * [MAX_EMS] wide (at letter size 快速输入 took three keys); one character as a symbol. Words shrink to the least size,
 * then are cut with [ELLIPSIS]; one line. Sizes are the scene's `Popups.SYMBOL_DP`, `WORDS_DP`, `MIN_WORDS_DP`.
 */
object PreviewLabel {

    /** In characters of the words' size (恢复刚清除的 shrinks a little). */
    const val MAX_EMS = 5

    const val ELLIPSIS = "…"

    /** Far more than a bubble shows, so a cut text is told by [ELLIPSIS]. */
    const val MAX_TEXT = 40

    data class Fit(val text: String, val size: Float)

    /** One character as seen (❤️, a flag, a family) is a symbol. */
    fun isWords(label: String): Boolean = oneLine(label).let { it.cut || characters(it.body) > 1 }

    /** Padding left out: [MAX_EMS] of [size], or the key's face where wider. */
    fun room(keyWidth: Float, size: Float, padding: Float): Float = maxOf(keyWidth - 2 * padding, MAX_EMS * size)

    /** [measure] proportional to size; cut keeps whole characters as seen. A text past [MAX_TEXT] is cut already. */
    fun fit(label: String, size: Float, minSize: Float, room: Float, measure: (String, Float) -> Float): Fit {
        val line = oneLine(label)
        val whole = line.shown
        val width = measure(whole, size)
        if (width <= room) return Fit(whole, size)
        val shrunk = size * room / width
        if (shrunk >= minSize) return Fit(whole, shrunk)
        val body = line.body
        val bounds = BreakIterator.getCharacterInstance().apply { setText(body) }
        var end = body.length
        while (end > 0) {
            end = bounds.preceding(end)
            val cut = body.substring(0, end).trimEnd() + ELLIPSIS
            if (measure(cut, minSize) <= room) return Fit(cut, minSize)
        }
        return Fit(ELLIPSIS, minSize)
    }

    private val SPACES = Regex("\\s+")

    // [cut] when more than spaces went, to be told by the ellipsis
    private class Line(val body: String, val cut: Boolean) {
        val shown: String get() = if (cut) body + ELLIPSIS else body
    }

    // ended where a character as seen ends (not in an emoji, a flag, a family)
    private fun oneLine(text: String): Line {
        var end = text.length
        if (end > MAX_TEXT) {
            val bounds = BreakIterator.getCharacterInstance().apply { setText(text) }
            end = if (bounds.isBoundary(MAX_TEXT)) MAX_TEXT else bounds.preceding(MAX_TEXT)
        }
        return Line(text.substring(0, end).replace(SPACES, " ").trim(), text.substring(end).isNotBlank())
    }

    // characters as seen
    private fun characters(text: String): Int {
        val bounds = BreakIterator.getCharacterInstance().apply { setText(text) }
        var n = 0
        while (bounds.next() != BreakIterator.DONE) n++
        return n
    }
}
