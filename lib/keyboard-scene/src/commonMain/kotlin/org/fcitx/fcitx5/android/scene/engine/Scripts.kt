/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

/**
 * A text cut where its script changes, for a theme's `font-han` and `font-latin` (060-render-v2 §16-4):
 * each run set in its own font, measured on its own and drawn on from where the one before ends. The
 * engine cuts and measures, so every backend draws the runs it was given, in the fonts it was given:
 * nothing is left to a platform's fallback, which takes a glyph from the first font that has it (a
 * Latin font with CJK glyphs would take the Han characters too).
 *
 * Han: the CJK ideographs and their radicals, the CJK symbols and punctuation (，。「」), the full-width
 * forms (！？Ａ１) — what a CJK font is drawn for. Latin: ASCII's printing characters (letters, digits,
 * punctuation) and the Latin letters beyond (é, ß, ā). The rest (emoji, 注音, symbols) is in the key's
 * `font`. A space (any Unicode space, a no-break one too) is of no script: it goes with the run before
 * it, or the one after it where it leads, so `拼音 Pinyin` is two runs, not three. So is what only
 * changes the character before it (a combining mark, a keycap's enclosing mark, a variation selector,
 * a zero-width joiner, a skin tone): `1️⃣`, `ê̄`, an ideograph's variant stay one glyph, in its font.
 * Not Han: the general punctuation a Chinese text also uses (“ ” … — ·), nor the half-width katakana
 * and Hangul among the full-width forms' block; the later CJK extensions (H, I) and strokes neither.
 */
object Scripts {
    enum class Script { Han, Latin, Other }

    /** [text] in runs of one font each, [font] where [han] or [latin] (null) gives none; one run where neither is given. */
    fun runs(text: String, font: String, han: String?, latin: String?): List<Pair<String, String>> {
        if (han == null && latin == null) return listOf(text to font)
        val out = ArrayList<Pair<String, String>>()
        var start = 0
        var current: String? = null
        var i = 0
        while (i < text.length) {
            val cp = codePointAt(text, i)
            // a space or a joiner: the run it is in goes on (a leading one's is the first run that has a font)
            val f = if (cp <= MAX_BMP && cp.toChar().isWhitespace() || JOINERS.any { cp in it }) {
                null
            } else {
                when (of(cp)) {
                    Script.Han -> han ?: font
                    Script.Latin -> latin ?: font
                    Script.Other -> font
                }
            }
            if (f != null && current != null && f != current) {
                out += text.substring(start, i) to current
                start = i
            }
            if (f != null) current = f
            i += if (cp > MAX_BMP) 2 else 1
        }
        out += text.substring(start) to (current ?: font)
        return out
    }

    fun of(cp: Int): Script = when {
        HAN.any { cp in it } -> Script.Han
        LATIN.any { cp in it } -> Script.Latin
        else -> Script.Other
    }

    // a surrogate pair's code point; a lone surrogate as itself
    private fun codePointAt(s: String, i: Int): Int {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            return ((c.code - HIGH) shl TEN_BITS) + (s[i + 1].code - LOW) + SUPPLEMENTARY
        }
        return c.code
    }

    private const val MAX_BMP = 0xFFFF
    private const val HIGH = 0xD800
    private const val LOW = 0xDC00
    private const val SUPPLEMENTARY = 0x10000
    private const val TEN_BITS = 10

    private val HAN = listOf(
        0x2E80..0x2FDF, // radicals, Kangxi radicals
        0x3000..0x303F, // CJK symbols and punctuation
        0x3400..0x4DBF, // extension A
        0x4E00..0x9FFF, // unified ideographs
        0xF900..0xFAFF, // compatibility ideographs
        0xFF00..0xFF64, // full-width forms, half-width CJK punctuation (not the half-width katakana, Hangul)
        0xFFE0..0xFFEF, // full-width signs (￥￠), half-width arrows
        0x20000..0x3134F, // extensions B–G, compatibility supplement
    )

    private val JOINERS = listOf(
        0x0300..0x036F, // combining diacritical marks
        0x200D..0x200D, // zero-width joiner
        0x20D0..0x20FF, // combining marks for symbols (U+20E3, a keycap)
        0xFE00..0xFE0F, // variation selectors
        0x1F3FB..0x1F3FF, // skin tone modifiers
        0xE0100..0xE01EF, // variation selectors supplement
    )

    private val LATIN = listOf(
        0x21..0x7E, // ASCII's printing characters
        0xC0..0xD6, 0xD8..0xF6, 0xF8..0x24F, // Latin-1's letters, Latin Extended-A and -B
        0x1E00..0x1EFF, // Latin Extended Additional (ạ, ế)
    )
}
