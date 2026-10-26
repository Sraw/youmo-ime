/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

/**
 * What pinyin lists beside the readings of the input, text of no word as a phrase is: the first
 * reading's emoji ([EmojiCandidates]), and the characters the input spells by their parts after
 * [Chaizi.KEY] ([Chaizi]), first, as what was asked for: the u is a slip of no pinyin otherwise,
 * but for one of y before a vowel.
 */
object AsideCandidates {

    private const val VOWELS = "aeiou"

    /** The [candidates] listed, and those of them set aside, by text, each with its hint (a character's reading). */
    class Placed<T>(val candidates: List<T>, val aside: Map<String, String>)

    /**
     * [read] (the readings of [key], the input) with the emoji and the characters by their parts
     * among them, made by [make]; [chaizi] null where the keys are no pinyin to spell parts by (双拼).
     * The emoji are those of [first], the reading shown first, asked only for them, after a word [lifted] to their place.
     * Nothing placed, the candidates are [read] itself (see [EmojiCandidates.placed]), as PinyinSession.place relies on.
     */
    fun <T> place(
        read: List<T>,
        key: String,
        emoji: (String) -> List<String>,
        chaizi: ((String) -> List<Pair<String, String>>)?,
        text: (T) -> String,
        whole: (T) -> Boolean,
        make: (String) -> T,
        phrases: (String) -> List<String> = { emptyList() },
        first: () -> T? = { read.firstOrNull() },
        lifted: (T) -> Boolean = { false },
    ): Placed<T> {
        val parts = if (chaizi == null || key.firstOrNull() != Chaizi.KEY) emptyList() else chaizi(key.substring(1).filter { it in 'a'..'z' })
        if (parts.isEmpty()) {
            val (withEmoji, added) = EmojiCandidates.placed(read, first(), emoji, text, whole, phrases, lifted, make)
            return Placed(withEmoji, added.associateWith { "" })
        }
        // no emoji of a reading of u…, the readings being slips; but u then a vowel may be a slip for
        // y (uao for yao): the reading first, then the characters
        val others = read.filter { c -> parts.none { it.first == text(c) } }
        val at = if (key.length > 1 && key[1] in VOWELS) minOf(1, others.size) else 0
        return Placed(others.subList(0, at) + parts.map { make(it.first) } + others.subList(at, others.size), parts.toMap())
    }
}
