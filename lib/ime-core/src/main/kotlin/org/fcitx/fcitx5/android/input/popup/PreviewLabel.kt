/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

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

    /** Whether [label] is words, sized here, not one character shown as a symbol is. */
    fun isWords(label: String): Boolean = oneLine(label).let { it.codePointCount(0, it.length) > 1 }

    /** The widest the words' text may be, padding left out: [MAX_EMS] of [size], or the key's face where wider. */
    fun room(keyWidth: Float, size: Float, padding: Float): Float = maxOf(keyWidth - 2 * padding, MAX_EMS * size)

    /**
     * [label] fitted into [room] ([measure] its width at a size, proportional to it): at [size] where
     * it fits, else as much smaller as it needs down to [minSize], else at [minSize] cut short with
     * [ELLIPSIS], whole characters kept (an emoji's two halves together).
     */
    fun fit(label: String, size: Float, minSize: Float, room: Float, measure: (String, Float) -> Float): Fit {
        val line = oneLine(label)
        val width = measure(line, size)
        if (width <= room) return Fit(line, size)
        val shrunk = size * room / width
        if (shrunk >= minSize) return Fit(line, shrunk)
        var end = line.length
        while (end > 0) {
            end = line.offsetByCodePoints(end, -1)
            val cut = line.substring(0, end).trimEnd() + ELLIPSIS
            if (measure(cut, minSize) <= room) return Fit(cut, minSize)
        }
        return Fit(ELLIPSIS, minSize)
    }

    private val SPACES = Regex("\\s+")

    // its first MAX_TEXT chars, an emoji's two halves together
    private fun oneLine(text: String): String {
        val end = if (text.length > MAX_TEXT && text[MAX_TEXT - 1].isHighSurrogate()) MAX_TEXT - 1 else MAX_TEXT
        return text.take(end).replace(SPACES, " ").trim()
    }
}
