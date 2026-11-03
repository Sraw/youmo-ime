/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import java.text.BreakIterator

/**
 * What a key's preview bubble shows of the shortcut a swipe reaches: a command's name (全选, 快速输入,
 * 恢复刚清除的) or a text the user set, as words. At the letter's size (23 sp) and as wide as they
 * came, 快速输入 was a bubble three keys wide and a long text half the keyboard's, over the bar
 * (the user, 0.5.1's test build): words are smaller, the bubble no wider than [MAX_EMS] of them.
 *
 * A label of one character (a text shortcut 你, an emoji) is not words: as large as a symbol's.
 * Words that do not fit get smaller, down to [MIN_SP]; still too long, their end is cut with
 * [ELLIPSIS]. A text's line breaks and runs of spaces are one space: the bubble has one line.
 * Sizes and widths are the caller's units (pixels), the ratios here.
 */
object PreviewLabel {

    /** A character's size (a letter, a symbol, a label of one), in sp before the keys' scale: a letter key's. */
    const val SYMBOL_SP = 23f

    /**
     * A character's size at the keys' text [scale] (紧凑 0.9, floating by its width), in sp: the
     * bubble's letter grows and shrinks with the key's under it, as the words do (team-lead, 0.5.1).
     */
    fun symbolSize(scale: Float): Float = SYMBOL_SP * scale

    /** The words' size, in sp before the keys' scale (紧凑, floating): a function key's label's. */
    const val WORDS_SP = 16f

    /** The least the words shrink to, in sp before the keys' scale. */
    const val MIN_SP = 12f

    /** The widest a bubble of words is: this many characters of [WORDS_SP] (恢复刚清除的 shrinks a little). */
    const val MAX_EMS = 5

    const val ELLIPSIS = "…"

    /** The most of a text measured: far more than a bubble shows, so a cut text is told by [ELLIPSIS]. */
    const val MAX_TEXT = 40

    /** [text] to show, at [size]. */
    data class Fit(val text: String, val size: Float)

    /** Whether [label] is words, sized here, not one character (as seen: ❤️, a flag, a family) shown as a symbol is. */
    fun isWords(label: String): Boolean = oneLine(label).let { it.cut || characters(it.body) > 1 }

    /** The widest the words' text may be, padding left out: [MAX_EMS] of [size], or the key's face where wider. */
    fun room(keyWidth: Float, size: Float, padding: Float): Float = maxOf(keyWidth - 2 * padding, MAX_EMS * size)

    /**
     * [label] fitted into [room] ([measure] its width at a size, proportional to it): at [size] where
     * it fits, else as much smaller as it needs down to [minSize], else at [minSize] cut short with
     * [ELLIPSIS], whole characters as seen kept (an emoji's halves, a flag, a family, an accent together).
     * A text past [MAX_TEXT] is cut already: its [ELLIPSIS] shown however it fits.
     */
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

    // a label on one line: [cut] when more of it went than spaces, to be told by the ellipsis
    private class Line(val body: String, val cut: Boolean) {
        val shown: String get() = if (cut) body + ELLIPSIS else body
    }

    // its first MAX_TEXT chars, ended where a character as seen ends (not in an emoji, a flag, a family)
    private fun oneLine(text: String): Line {
        var end = text.length
        if (end > MAX_TEXT) {
            val bounds = BreakIterator.getCharacterInstance().apply { setText(text) }
            end = if (bounds.isBoundary(MAX_TEXT)) MAX_TEXT else bounds.preceding(MAX_TEXT)
        }
        return Line(text.substring(0, end).replace(SPACES, " ").trim(), text.substring(end).isNotBlank())
    }

    // how many characters as seen
    private fun characters(text: String): Int {
        val bounds = BreakIterator.getCharacterInstance().apply { setText(text) }
        var n = 0
        while (bounds.next() != BreakIterator.DONE) n++
        return n
    }
}
