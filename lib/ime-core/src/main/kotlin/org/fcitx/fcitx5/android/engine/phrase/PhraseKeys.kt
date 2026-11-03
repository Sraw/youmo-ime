/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph

/**
 * Input typed on keys of letter groups (the nine keys, the 14 keys, 双拼's 18 and nine keys, English's
 * nine keys), as the custom phrases' keys of letters it may be (A1): its input is the keys' codes,
 * never a phrase's key as the user wrote it. Each code is any letter of its key, so one input reads
 * as several keys, every one of them offered, as the nine keys offer each syllable their digits type.
 *
 * What the user fixed must agree: a letter typed as itself (upper case in the input, any case on
 * English's nine keys) is that letter, and a syllable or initial taken off the bar (its letters
 * then a separator) is what [of]'s `taken` spells it as in a key: its own letters on full pinyin's
 * keyboards, 双拼's keys for it on 双拼's. A separator splits syllables, which a key has none of:
 * skipped.
 */
class PhraseKeys private constructor(
    // a place of the input each: the letters, or the runs of them, a key may have there
    private val slots: List<List<String>>,
) {
    // the lengths a key read may have: each place one of its ways
    private val shortest = slots.sumOf { slot -> slot.minOf { it.length } }
    private val longest = slots.sumOf { slot -> slot.maxOf { it.length } }

    /** The lengths a key read may have: the phrases' keys of these alone are worth trying. */
    val lengths: IntRange get() = shortest..longest

    /** Whether a key [length] long may be read, as [lengths] has it. */
    fun fits(length: Int): Boolean = length in lengths

    /** Whether the input reads as [key]: each place of it, in turn, one of its ways in the key, and nothing of the key left. */
    fun reads(key: String): Boolean {
        if (slots.isEmpty()) return false
        // two arrays for every place, swapped: one key's read allocates no more as the input grows
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
        /**
         * [typed] on keys of [letters], as a phrase's key may be; null where it can be none (a
         * character no key types, nothing but separators). [taken] spells a syllable taken off the
         * bar, the lower-case letters before a separator, in a key's letters: each way it may be.
         * [ways], where not null, are the letters a character of [typed] may be in a key, before
         * anything else: a zhuyin key's in pinyin (ㄧ i, y or yi), an empty list skipped (a tone).
         */
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
