/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

/** What pinyin lists beside the input's readings: the first reading's emoji, and after [Chaizi.KEY] the characters spelled by their parts. */
object AsideCandidates {

    private const val VOWELS = "aeiou"

    /** [aside]: the set-aside candidates by text, each with its hint (a character's reading). */
    class Placed<T>(val candidates: List<T>, val aside: Map<String, String>)

    /** [read] with emoji (of [first], after a word [lifted]) and part-spelled characters placed among them; [chaizi] null where keys are no pinyin (双拼). With nothing placed the result is [read] itself, which PinyinSession.place relies on. */
    fun <T> place(
        read: List<T>,
        key: String,
        emoji: (String) -> List<String>,
        chaizi: PartsLookup?,
        text: (T) -> String,
        whole: (T) -> Boolean,
        make: (String) -> T,
        phrases: (String) -> List<String> = { emptyList() },
        first: () -> T? = { read.firstOrNull() },
        lifted: (T) -> Boolean = { false },
    ): Placed<T> {
        val parts = if (chaizi == null || key.firstOrNull() != Chaizi.KEY) emptyList() else chaizi.of(key.substring(1).filter { it in 'a'..'z' })
        if (parts.isEmpty()) {
            val (withEmoji, added) = EmojiCandidates.placed(read, first(), emoji, text, whole, phrases, lifted, make)
            return Placed(withEmoji, added.associateWith { "" })
        }
        // no emoji for u… readings (slips); but u before a vowel may be a slip for y (uao): reading first, then characters
        val others = read.filter { c -> parts.none { it.first == text(c) } }
        val at = if (key.length > 1 && key[1] in VOWELS) minOf(1, others.size) else 0
        return Placed(others.subList(0, at) + parts.map { make(it.first) } + others.subList(at, others.size), parts.toMap())
    }
}
