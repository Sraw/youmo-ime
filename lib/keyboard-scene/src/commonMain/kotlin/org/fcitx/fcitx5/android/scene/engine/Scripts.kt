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
 * punctuation) and the Latin letters beyond (é, ß, ā). The rest (emoji, 注音, symbols, spaces) is in
 * the key's `font`.
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
            val f = when (of(cp)) {
                Script.Han -> han ?: font
                Script.Latin -> latin ?: font
                Script.Other -> font
            }
            if (current != null && f != current) {
                out += text.substring(start, i) to current
                start = i
            }
            current = f
            i += if (cp > MAX_BMP) 2 else 1
        }
        if (current != null) out += text.substring(start) to current
        return out.ifEmpty { listOf(text to font) }
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
        0xFF00..0xFFEF, // half- and full-width forms
        0x20000..0x3134F, // extensions B–G, compatibility supplement
    )

    private val LATIN = listOf(
        0x21..0x7E, // ASCII's printing characters
        0xC0..0xD6, 0xD8..0xF6, 0xF8..0x24F, // Latin-1's letters, Latin Extended-A and -B
        0x1E00..0x1EFF, // Latin Extended Additional (ạ, ế)
    )
}
