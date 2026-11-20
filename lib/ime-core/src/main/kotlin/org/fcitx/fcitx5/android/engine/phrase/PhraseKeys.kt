/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph

/**
 * Input typed on grouped keys, as the custom-phrase keys it may be: each code is any letter of its key, so
 * one input reads as several keys, all offered. A letter typed as itself is that letter; a syllable taken off
 * the bar is what [of]'s `taken` spells; separators are skipped.
 */
class PhraseKeys private constructor(
    // per input place: the letters (or runs) a key may have there
    private val slots: List<List<String>>,
) {
    private val shortest = slots.sumOf { slot -> slot.minOf { it.length } }
    private val longest = slots.sumOf { slot -> slot.maxOf { it.length } }

    /** Only phrase keys of these lengths are worth trying. */
    val lengths: IntRange get() = shortest..longest

    fun fits(length: Int): Boolean = length in lengths

    /** Each place in turn is one of its ways in [key], with nothing of [key] left. */
    fun reads(key: String): Boolean {
        if (slots.isEmpty()) return false
        // two arrays swapped per place: no allocation as the input grows
        var at = BooleanArray(key.length + 1).also { it[0] = true }
        var next = BooleanArray(key.length + 1)
        for (slot in slots) {
            next.fill(false)
            var any = false
            for (p in 0 until key.length) {
                if (!at[p]) continue
                for (way in slot) {
                    if (key.startsWith(way, p)) {
                        next[p + way.length] = true
                        any = true
                    }
                }
            }
            if (!any) return false
            at = next.also { next = at }
        }
        return at[key.length]
    }

    companion object {
        /** [typed] on keys of [letters] as a phrase key may be; null if it can be none. [taken] spells a bar syllable each way; [ways], if given, overrides a character's letters (zhuyin in pinyin; empty skips a tone). */
        fun of(
            typed: CharSequence,
            letters: NineKeyLetters,
            ways: (Char) -> List<String>? = { null },
            taken: (String) -> List<String> = { listOf(it) },
        ): PhraseKeys? {
            val slots = ArrayList<List<String>>(typed.length)
            var i = 0
            while (i < typed.length) {
                val c = typed[i]
                val spelt = ways(c)
                when {
                    spelt != null -> {
                        if (spelt.isNotEmpty()) slots += spelt
                        i++
                    }
                    c == SyllableGraph.SEPARATOR -> i++
                    letters.isCode(c) -> {
                        val on = letters.letters(c)
                        if (on.isEmpty()) return null
                        slots += on.map(Char::toString)
                        i++
                    }
                    c in 'A'..'Z' -> {
                        slots += listOf(c.lowercase())
                        i++
                    }
                    c in 'a'..'z' -> {
                        var end = i
                        while (end < typed.length && typed[end] in 'a'..'z') end++
                        val ways = taken(typed.substring(i, end))
                        if (ways.isEmpty()) return null
                        slots += ways
                        i = end
                    }
                    else -> return null
                }
            }
            return if (slots.isEmpty()) null else PhraseKeys(slots)
        }
    }
}
