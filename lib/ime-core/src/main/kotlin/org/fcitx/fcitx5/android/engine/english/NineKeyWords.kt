/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.SlipCost
import java.util.BitSet

/** Words the nine keys may spell for English, exact length first, in the typed case. */
internal class NineKeyWords(data: EnglishData, private val letters: NineKeyLetters, slip: SlipCost = SLIP, score: (context: Long, word: Int) -> Float) {

    private val fits = EnglishDecoder.nineKeyCost(letters::letters)

    // a tap near another key reads it too, as pinyin's nine keys
    val decoder = EnglishDecoder(
        data, EnglishDecoder.NINE_KEY_COSTS.copy(limit = -slip.floor), fits, EnglishDecoder.nineKeyTap(letters::letters, slip), slip.legal, score,
    )

    class Found(val text: String, val score: Float, val completes: Boolean)

    fun keys(input: CharSequence): String = input.filter { it != '\'' }.toString().lowercase()

    fun own(word: String, keys: String, score: Float): Found? {
        val key = EnglishData.key(word)
        val spelt = key.length >= keys.length && keys.indices.all { fits(key[it], keys[it]) == 0f }
        return if (spelt) Found(word, score, key.length > keys.length) else null
    }

    fun ordered(found: List<Found>, input: CharSequence): List<String> = found
        .filter { f -> input.indices.all { input[it] != '\'' || f.text.getOrNull(it) == '\'' } }
        .sortedWith(compareBy<Found> { it.completes }.thenByDescending { it.score })
        .map { it.text }

    fun firstLetters(input: CharSequence, upper: BitSet): String =
        inCase(input.map { if (it in '2'..'9') letters.letters(it).firstOrNull()?.lowercaseChar() ?: it else it }.joinToString(""), input, upper)

    /** [word] in [input]'s case: all Shift, all capitals; else a capital where a key had Shift; never one taken away (London). */
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

    private companion object {
        val SLIP = SlipCost(floor = -3f)
    }
}
