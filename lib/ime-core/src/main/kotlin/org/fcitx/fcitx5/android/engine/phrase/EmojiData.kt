/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

/**
 * The emoji, as the app has them (`engine/emoji.data`, ime-dict-tool's `emoji`): the emoji panel's
 * [groups], Unicode's (emoji-test.txt) in its order, and the Chinese words each stands for, from
 * CLDR's keywords and this project's own (`lexicon/emoji.tsv`): 哈哈 😃 😄, 点赞 👍, 晚安 😴 ([of]).
 *
 * The Chinese input methods offer a word's emoji among its candidates ([EmojiCandidates]); the
 * panel lists the groups, and finds emoji by their words' pinyin ([search]). The file is text: a
 * group a line, `@<id><TAB>emoji...`, then a word a line, `<word><TAB>emoji...[<TAB>pinyin]`, the
 * emoji apart by spaces, a word's likeliest first, its syllables apart by spaces too.
 */
class EmojiData private constructor(
    /** The panel's groups by their ids ([GROUPS]), each emoji written as the emoji keyboard types it. */
    val groups: List<Pair<String, List<String>>>,
    private val words: Map<String, List<String>>,
    // each word's syllables, in the file's order (by word): what [search] goes through
    private val pinyin: List<Pair<String, List<String>>> = emptyList(),
) {

    /**
     * The emoji of the words [query] spells, at most [limit]: typed in full (`xiao` 笑), the start of
     * a word in full (`xiaol` 笑脸), or each syllable by its start (`dz` 点赞, `xiaol` too); in that
     * order, a word of fewer syllables first, then as the file has them. Letters only count; none for none.
     */
    fun search(query: String, limit: Int = SEARCH_LIMIT): List<String> {
        val q = query.lowercase().filter { it in 'a'..'z' }
        if (q.isEmpty()) return emptyList()
        val found = pinyin.mapNotNull { (word, syllables) ->
            match(q, syllables, 0, 0, partly = false)?.let { Triple(it, syllables.size, word) }
        }.sortedWith(compareBy({ it.first }, { it.second }))
        return found.asSequence().flatMap { of(it.third) }.distinct().take(limit).toList()
    }

    /**
     * How [q] from [at] spells [syllables] from [from]: [EXACT], [START] or [SHORT] ([search]'s
     * order), the best of the ways; null for none. [partly]: a syllable before was typed in part.
     */
    private fun match(q: String, syllables: List<String>, from: Int, at: Int, partly: Boolean): Int? {
        if (from == syllables.size) return null
        val s = syllables[from]
        var best: Int? = null
        for (k in minOf(s.length, q.length - at) downTo 1) {
            val m = when {
                !q.regionMatches(at, s, 0, k) -> null
                at + k < q.length -> match(q, syllables, from + 1, at + k, partly || k < s.length)
                partly -> SHORT
                k == s.length && from + 1 == syllables.size -> EXACT
                // the last typed in part, or the word goes on
                else -> START
            }
            if (m != null && (best == null || m < best)) best = m
        }
        return best
    }

    /** The emoji [word] stands for, the likeliest first; none for most words. */
    fun of(word: String): List<String> = words[word].orEmpty()

    /** How many words have emoji. */
    val size get() = words.size

    companion object {
        val EMPTY = EmojiData(emptyList(), emptyMap())

        /** How many emoji [search] gives at most. */
        const val SEARCH_LIMIT = 64

        private const val EXACT = 0
        private const val START = 1
        private const val SHORT = 2

        /** The panel's groups, Unicode's but its skin tones and hair (Component): their ids, in its order. */
        val GROUPS = listOf("smileys", "people", "nature", "food", "travel", "activities", "objects", "symbols", "flags")

        /** A word no longer than this is a keyword: a line with a longer one is skipped. */
        const val MAX_WORD = 8

        /** At most this many emoji are kept of a word. */
        const val MAX_EMOJI = 8

        /** What [text] has, as [format] writes it. A line that is neither is skipped, as are `#` comments. */
        fun read(text: CharSequence): EmojiData {
            val groups = ArrayList<Pair<String, List<String>>>()
            val words = HashMap<String, List<String>>()
            val pinyin = ArrayList<Pair<String, List<String>>>()
            for (line in text.lineSequence()) {
                // a comment's # is no word: the tab is looked for past it
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

        /**
         * [groups] and [words] as [read] takes them, the words in their order (what [search] lists
         * first of words alike), each with its [pinyin] if any (`ha ha`), [header] a comment above.
         */
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
