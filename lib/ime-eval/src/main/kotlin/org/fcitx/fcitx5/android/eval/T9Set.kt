/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter

/**
 * Types the full-pinyin samples of an evaluation set on the nine keys (九键), as [ShuangpinSet]
 * does in 双拼: the [ExactReading] spelt in digits, no separators, as a phone's user types it.
 * Samples with a Latin letter are left out: the nine keys type it in another mode. How a key is
 * pressed is [Presses]': once a letter, or with 连按 on, as the app hands the engine a press of the
 * same key again within its timeout ([T9Segmenter.again]).
 */
object T9Set {

    /** The scheme whose segmenter reads 简拼: [T9Segmenter]'s `abbreviations`. */
    const val ABBREVIATING = "t9"

    /** The segmenters measured: `t9` reads 简拼 where no longer syllable starts, `t9-strict` only where nothing else is read. */
    val SCHEMES = setOf(ABBREVIATING, "t9-strict")

    /** How each letter's key is pressed, `t9 --presses`. */
    enum class Presses(val option: String) {
        /** Once, 连按 off or a pause before each press: 744 for shi. */
        ONCE("once"),

        /** Once, quickly: a press of the key just pressed is one again, 74 and 4 again. */
        FAST("fast"),

        /** As 连按 spells a letter, its key as many times as its place on it, quickly: s is 7777. */
        LETTERS("letters");

        companion object {
            fun of(option: String): Presses? = entries.find { it.option == option }
        }
    }

    fun convert(samples: List<Sample>, presses: Presses = Presses.ONCE): List<Sample> = samples.mapNotNull { sample ->
        val reading = ExactReading.of(sample) { !Syllables.spelling(it)[0].isUpperCase() } ?: return@mapNotNull null
        val letters = reading.joinToString("") { Syllables.spelling(it.syllable) }
        sample.copy(input = press(letters, presses) ?: return@mapNotNull null)
    }

    // [letters] as the keys pressed for them, null where one is on no key
    private fun press(letters: String, presses: Presses): String? {
        val keys = NineKeyLetters.STANDARD
        val out = StringBuilder()
        for (c in letters) {
            val key = keys.keysOf(c).firstOrNull() ?: return null
            val times = if (presses == Presses.LETTERS) keys.letters(key).indexOf(c) + 1 else 1
            repeat(times) { n ->
                // a letter's first press on the key the last letter ended on comes after a pause: not again
                val again = presses == Presses.LETTERS && n > 0 ||
                    presses == Presses.FAST && out.isNotEmpty() && T9Segmenter.digitOf(out.last()) == key
                out.append(if (again) T9Segmenter.again(key) else key)
            }
        }
        return out.toString()
    }
}
