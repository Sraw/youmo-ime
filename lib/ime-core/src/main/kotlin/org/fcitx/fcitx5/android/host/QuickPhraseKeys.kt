/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.Keyboard

/** A key of several letters is any of them, as custom phrases read letter groups (the nine keys' 4 finds `gh`); [shown] is the code row's. */
class QuickPhraseKey(val shown: Int, val can: Set<Int>)

object QuickPhraseKeys {

    /** Digit keys stand for their letters and the digit, shown as the digit; a 14/18 key's code or tap its letters, shown as [Keyboard.typedLetter]; null: types nothing here. */
    @Suppress("ReturnCount")
    fun of(codePoint: Int, nine: NineKeyLetters): QuickPhraseKey? {
        digitOf(codePoint)?.let { d -> return digit(d, nine.letters(d)) }
        if (codePoint in Keyboard.NINE_LETTERS) {
            val n = codePoint - Keyboard.NINE_LETTERS.first
            return letter((if (n >= ALPHABET) 'A' else 'a') + n % ALPHABET)
        }
        if (codePoint in Keyboard.LETTERS) return letter('a' + (codePoint - Keyboard.LETTERS.first) % ALPHABET)
        if (codePoint in NineKeyLetters.NINE_SP_BLOCK) {
            val c = codePoint.toChar()
            // 微软's ; is 1's while typing: shown as the separator, as 1 is on the other schemes' keys; a phrase's ; matches it too
            if (c == NineKeyLetters.NINE_SP_SEMICOLON) return QuickPhraseKey('\''.code, setOf('\''.code, ';'.code))
            return digit('2' + NineKeyLetters.NINE_SP.codes.indexOf(c), NineKeyLetters.NINE_SP.letters(c))
        }
        val shown = Keyboard.typedLetter(codePoint)
        if (shown != null) {
            // a tap's code, its place on the key dropped: the whole key's letters
            val code = Keyboard.tappedCode(codePoint)?.code ?: Keyboard.keyedChar(codePoint)?.code ?: codePoint
            val group = NineKeyLetters.ofCode(code)?.letters(code.toChar())
            return QuickPhraseKey(shown.code, group?.ifEmpty { null }?.let(::either) ?: setOf(shown.code))
        }
        if (KeySyms.isPrivateUse(codePoint)) return null
        return QuickPhraseKey(codePoint, setOf(codePoint))
    }

    // the pinyin and English nine keys' digit keys
    private fun digitOf(codePoint: Int): Char? = when (codePoint) {
        in Keyboard.PINYIN_NINE -> '0' + (codePoint - Keyboard.PINYIN_NINE.first)
        in Keyboard.NINE_DIGITS -> '0' + (codePoint - Keyboard.NINE_DIGITS.first) % DIGITS
        else -> null
    }

    private fun digit(d: Char, letters: String) = QuickPhraseKey(d.code, either(letters) + d.code)

    // a key's letters have no case: either reads, a built-in key's capitals (fcitx's case kept) too
    private fun either(letters: String): Set<Int> = (letters.lowercase() + letters.uppercase()).map { it.code }.toSet()

    private fun letter(c: Char) = QuickPhraseKey(c.code, setOf(c.code))

    private const val ALPHABET = 26
    private const val DIGITS = 10
}
