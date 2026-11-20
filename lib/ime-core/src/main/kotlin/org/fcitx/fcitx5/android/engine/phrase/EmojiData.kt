/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

/**
 * The emoji (`engine/emoji.data`): the panel's Unicode [groups] and the Chinese words each stands for
 * (CLDR keywords plus `lexicon/emoji.tsv`; [of]); the panel searches them by pinyin ([search]). Lines:
 * `@<id><TAB>emoji...` per group, `<word><TAB>emoji...[<TAB>pinyin]` per word.
 */
class EmojiData private constructor(
    /** By id ([GROUPS]), each emoji as the emoji keyboard types it. */
    val groups: List<Pair<String, List<String>>>,
    private val words: Map<String, List<String>>,
    // each word's syllables, in file order: what [search] scans
    private val pinyin: List<Pair<String, List<String>>> = emptyList(),
) {

    /** Emoji of words [query] spells: in full (`xiao`), a word's start (`xiaol`), or syllable starts (`dz`); in that order, fewer syllables first. Letters only. */
    fun search(query: String, limit: Int = SEARCH_LIMIT): List<String> {
        val typed = query.lowercase().filter { it in 'a'..'z' }
        if (typed.isEmpty()) return emptyList()
        val found = pinyin.mapNotNull { (word, syllables) ->
            match(typed, syllables, 0, 0, partly = false)?.let { Triple(it, syllables.size, word) }
        }.sortedWith(compareBy({ it.first }, { it.second }))
        return found.asSequence().flatMap { of(it.third) }.distinct().take(limit).toList()
    }

    /** How [typed] from [at] spells [syllables] from [from]: [EXACT], [START] or [SHORT], the best; null for none. [partly]: an earlier syllable typed in part. */
    private fun match(typed: String, syllables: List<String>, from: Int, at: Int, partly: Boolean): Int? {
        if (from == syllables.size) return null
        val syllable = syllables[from]
        var best: Int? = null
        for (taken in minOf(syllable.length, typed.length - at) downTo 1) {
            val rank = when {
                !typed.regionMatches(at, syllable, 0, taken) -> null
                at + taken < typed.length -> match(typed, syllables, from + 1, at + taken, partly || taken < syllable.length)
                partly -> SHORT
                taken == syllable.length && from + 1 == syllables.size -> EXACT
                else -> START
            }
            if (rank != null && (best == null || rank < best)) best = rank
        }
        return best
    }

    fun of(word: String): List<String> = words[word].orEmpty()

    val size get() = words.size

    companion object {
        val EMPTY = EmojiData(emptyList(), emptyMap())

        const val SEARCH_LIMIT = 64

        private const val EXACT = 0
        private const val START = 1
        private const val SHORT = 2

        /** Unicode's groups except Component (skin tones, hair), in order. */
        val GROUPS = listOf("smileys", "people", "nature", "food", "travel", "activities", "objects", "symbols", "flags")

        /** Longer keywords are skipped. */
        const val MAX_WORD = 8

        const val MAX_EMOJI = 8

        /** Malformed lines and `#` comments are skipped. */
        fun read(text: CharSequence): EmojiData {
            val groups = ArrayList<Pair<String, List<String>>>()
            val words = HashMap<String, List<String>>()
            val pinyin = ArrayList<Pair<String, List<String>>>()
            for (line in text.lineSequence()) {
                // a comment's # is no word
                val fields = if (line.startsWith("#")) emptyList() else line.split('\t')
                val name = fields.firstOrNull().orEmpty()
                if (fields.size < 2 || name.isEmpty()) continue
                val emoji = fields[1].split(' ').filter { it.isNotEmpty() }
                when {
                    name[0] == '@' -> groups += name.substring(1) to emoji
                    name.length <= MAX_WORD && emoji.isNotEmpty() -> {
                        words[name] = emoji.distinct().take(MAX_EMOJI)
                        fields.getOrNull(2)?.split(' ')?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.let { pinyin += name to it }
                    }
                }
            }
            return EmojiData(groups, words, pinyin)
        }

        /** As [read] takes them; word order is [search]'s tie order. */
        fun format(
            groups: List<Pair<String, List<String>>>,
            words: Map<String, List<String>>,
            header: String = "",
            pinyin: Map<String, String?> = emptyMap(),
        ): String = buildString {
            for (line in header.lines()) if (line.isNotEmpty()) append("# ").append(line).append('\n')
            for ((id, emoji) in groups) append('@').append(id).append('\t').append(emoji.joinToString(" ")).append('\n')
            for ((word, emoji) in words) {
                if (emoji.isEmpty()) continue
                append(word).append('\t').append(emoji.joinToString(" "))
                pinyin[word]?.let { append('\t').append(it) }
                append('\n')
            }
        }
    }
}
