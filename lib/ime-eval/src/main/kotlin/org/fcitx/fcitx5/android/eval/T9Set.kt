/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.Syllables

/** Types an evaluation set's full-pinyin samples on the nine keys, the [ExactReading] spelt in digits; Latin samples left out. */
object T9Set {

    /** The scheme whose segmenter reads 简拼: [T9Segmenter]'s `abbreviations`. */
    const val ABBREVIATING = "t9"

    /** The 14 keys' (NineKeyLetters.G14) that reads 简拼 as [ABBREVIATING] does. */
    const val G14_ABBREVIATING = "g14"

    /** The 18 keys' (NineKeyLetters.G18) that reads 简拼 as [ABBREVIATING] does. */
    const val G18_ABBREVIATING = "g18"

    /** The segmenters measured: `t9`, `g14`, `g18` read 简拼 where no longer syllable starts, their `-strict` only where nothing else is read. */
    val SCHEMES: Set<String> get() = LETTERS.keys

    // the keys each of SCHEMES reads
    private val LETTERS = mapOf(
        ABBREVIATING to NineKeyLetters.STANDARD,
        "t9-strict" to NineKeyLetters.STANDARD,
        G14_ABBREVIATING to NineKeyLetters.G14,
        "g14-strict" to NineKeyLetters.G14,
        G18_ABBREVIATING to NineKeyLetters.G18,
        "g18-strict" to NineKeyLetters.G18,
    )

    /** The keys a scheme of [SCHEMES] reads: the 14 keys' for `g14…`, the 18 keys' for `g18…`, else the nine keys'. Another name fails. */
    fun lettersOf(scheme: String): NineKeyLetters = LETTERS[scheme] ?: throw IllegalArgumentException("no nine-key scheme $scheme")

    /** Whether [scheme] of [SCHEMES] reads 简拼 where no longer syllable starts: all but the `-strict` ones. */
    fun abbreviates(scheme: String): Boolean = !scheme.endsWith("-strict")

    /** The keyboards a set is typed on, `t9 --keys`: the nine keys' (the default), the 14 keys' or the 18 keys'. */
    val KEYS = mapOf("nine" to NineKeyLetters.STANDARD, "14" to NineKeyLetters.G14, "18" to NineKeyLetters.G18)

    /** How each letter's key is pressed, `t9 --presses`. */
    enum class Presses(val option: String) {
        /** Once, 连按 off or a pause before each press: 744 for shi. */
        ONCE("once"),

        /** As 连按 spells a letter: its key as many times as its place on it, quickly. */
        LETTERS("letters");

        companion object {
            fun of(option: String): Presses? = entries.find { it.option == option }
        }
    }

    /** [samples] typed on [keys]: the nine keys' by default, or a grouped keyboard's, which has no 连按. */
    fun convert(samples: List<Sample>, presses: Presses = Presses.ONCE, keys: NineKeyLetters = NineKeyLetters.STANDARD): List<Sample> {
        require(presses == Presses.ONCE || keys == NineKeyLetters.STANDARD) { "连按 is the nine keys' alone" }
        return samples.mapNotNull { sample ->
            val reading = ExactReading.of(sample) { !Syllables.spelling(it)[0].isUpperCase() } ?: return@mapNotNull null
            val letters = reading.joinToString("") { Syllables.spelling(it.syllable) }
            sample.copy(input = press(letters, presses, keys) ?: return@mapNotNull null)
        }
    }

    // [letters] as the keys pressed for them, null where one is on no key
    internal fun press(letters: String, presses: Presses, keys: NineKeyLetters): String? {
        val out = StringBuilder()
        for (c in letters) {
            val key = keys.keysOf(c).firstOrNull() ?: return null
            // a letter's first press on the key the last letter ended on comes after a pause: a key again
            out.append(if (presses == Presses.LETTERS && keys.letters(key).indexOf(c) > 0) c.uppercaseChar() else key)
        }
        return out.toString()
    }
}
