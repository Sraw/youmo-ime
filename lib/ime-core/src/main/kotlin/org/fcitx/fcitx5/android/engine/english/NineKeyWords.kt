/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import java.util.BitSet

/**
 * The words the nine keys typed for English may be ([EnglishSession] on the nine keys): those
 * whose letters are each on the key typed ([letters]), or the letter typed itself, as long as the
 * keys first, then longer ones; an apostrophe typed where the word has one; in the case typed.
 */
internal class NineKeyWords(data: EnglishData, private val letters: NineKeyLetters, score: (context: Long, word: Int) -> Float) {

    private val fits = EnglishDecoder.nineKeyCost(letters::letters)

    val decoder = EnglishDecoder(data, EnglishDecoder.NINE_KEY_COSTS, fits, score)

    /** A word offered: its [text], its [score], and whether it is longer than the keys. */
    class Found(val text: String, val score: Float, val completes: Boolean)

    /** What the decoder reads of [input]: its keys and letters, lower case, no apostrophe. */
    fun keys(input: CharSequence): String = input.filter { it != '\'' }.toString().lowercase()

    /** Whether [word] (one the data lacks) is spelled by [keys] or starts so: as [Found]. */
    fun own(word: String, keys: String, score: Float): Found? {
        val key = EnglishData.key(word)
        val spelt = key.length >= keys.length && keys.indices.all { fits(key[it], keys[it]) == 0f }
        return if (spelt) Found(word, score, key.length > keys.length) else null
    }

    /** [found] as offered for [input]: its apostrophes where typed, the keys' length first, best first. */
    fun ordered(found: List<Found>, input: CharSequence): List<String> = found
        .filter { f -> input.indices.all { input[it] != '\'' || f.text.getOrNull(it) == '\'' } }
        .sortedWith(compareBy<Found> { it.completes }.thenByDescending { it.score })
        .map { it.text }

    /** [input] with each key its first letter, the letters in it as they are, in its case ([inCase]). */
    fun firstLetters(input: CharSequence, upper: BitSet): String =
        inCase(input.map { if (it in '2'..'9') letters.letters(it).firstOrNull()?.lowercaseChar() ?: it else it }.joinToString(""), input, upper)

    /**
     * [word] in the case [input] was typed in, [upper] its keys typed with Shift: every key so, all
     * capitals; else a capital where a key was typed with it, a letter typed as one. Never a capital
     * taken away (London).
     */
    fun inCase(word: String, input: CharSequence, upper: BitSet): String {
        val keys = input.indices.filter { input[it] != '\'' }
        val capitals = keys.map { upper[it] || input[it].isUpperCase() }
        if (capitals.size > 1 && capitals.all { it }) return word.uppercase()
        var k = 0
        return buildString {
            for (c in word) {
                if (!c.isLetter()) {
                    append(c)
                    continue
                }
                append(if (capitals.getOrElse(k) { false }) c.uppercaseChar() else c)
                k++
            }
        }
    }
}
