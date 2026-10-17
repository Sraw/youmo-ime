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
 * panel lists the groups. The file is text: a group a line, `@<id><TAB>emoji...`, then a word a
 * line, `<word><TAB>emoji...`, the emoji apart by spaces, a word's likeliest first.
 */
class EmojiData private constructor(
    /** The panel's groups by their ids ([GROUPS]), each emoji written as the emoji keyboard types it. */
    val groups: List<Pair<String, List<String>>>,
    private val words: Map<String, List<String>>,
) {

    /** The emoji [word] stands for, the likeliest first; none for most words. */
    fun of(word: String): List<String> = words[word].orEmpty()

    /** How many words have emoji. */
    val size get() = words.size

    companion object {
        val EMPTY = EmojiData(emptyList(), emptyMap())

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
            for (line in text.lineSequence()) {
                // a comment's # is no word: the tab is looked for past it
                val tab = if (line.startsWith("#")) -1 else line.indexOf('\t')
                if (tab <= 0) continue
                val emoji = line.substring(tab + 1).split(' ').filter { it.isNotEmpty() }
                when {
                    line[0] == '@' -> groups += line.substring(1, tab) to emoji
                    tab <= MAX_WORD && emoji.isNotEmpty() -> words[line.substring(0, tab)] = emoji.distinct().take(MAX_EMOJI)
                }
            }
            return EmojiData(groups, words)
        }

        /** [groups] and [words] as [read] takes them, the words sorted, [header] a comment above. */
        fun format(groups: List<Pair<String, List<String>>>, words: Map<String, List<String>>, header: String = ""): String = buildString {
            for (line in header.lines()) if (line.isNotEmpty()) append("# ").append(line).append('\n')
            for ((id, emoji) in groups) append('@').append(id).append('\t').append(emoji.joinToString(" ")).append('\n')
            for ((word, emoji) in words.toSortedMap()) {
                if (emoji.isEmpty()) continue
                append(word).append('\t').append(emoji.joinToString(" ")).append('\n')
            }
        }
    }
}
