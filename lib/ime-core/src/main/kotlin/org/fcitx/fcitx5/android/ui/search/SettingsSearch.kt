/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.search

import java.text.Normalizer
import java.util.Locale

/**
 * Finds settings by what the user types: every word of the query somewhere in a setting's title,
 * the other names it goes by ([Entry.keywords]), its summary or the pages it is under, in any
 * case and either width. A choice of a list ([Kind.VALUE]) is found only when a word is in its own
 * name: 键盘高度 alone does not list every height. Ranked by [rank]: the title the query, then
 * titles that start with it (or another name that is it), titles with every word, other names
 * with every word, the rest; among equals the shorter title first (nearer what was typed), then
 * the order of the pages (dev/design/061-settings-search.md §4.5).
 *
 * @param T where a setting is: what opening a result needs
 */
class SettingsSearch<T>(entries: List<Entry<T>>) {

    /** What a result is: a page, a setting on it, one choice of a list, a command a key's swipe can run. */
    enum class Kind { PAGE, SETTING, VALUE, COMMAND }

    /**
     * A setting: what it is called, what it says under that, and the pages it is on, outermost
     * first; [id], the same in every language and version (page:, pref:, value:, cmd:), a result
     * kept once by; [keywords], what else a user may call it; [demoted], one ranked a step lower
     * (the developer's page).
     */
    data class Entry<T>(
        val title: String,
        val summary: String,
        val path: List<String>,
        val target: T,
        val id: String = "",
        val kind: Kind = Kind.SETTING,
        val keywords: List<String> = emptyList(),
        val demoted: Boolean = false,
    )

    /** A result: its entry, and [alias], the other name it was found by, to be said under it. */
    data class Found<T>(val entry: Entry<T>, val alias: String?) {
        val target: T get() = entry.target
    }

    /** An entry with its text normalised once: [find] runs on every key typed. */
    private class Indexed<T>(val entry: Entry<T>, val order: Int) {
        val title = norm(entry.title)
        val keywords = entry.keywords.map(::norm).filter { it.isNotEmpty() }
        val rest = norm(listOf(entry.summary, entry.path.joinToString(" ")).joinToString("\n"))
        val length = entry.title.codePointCount(0, entry.title.length)

        fun has(term: String) = term in title || keywords.any { term in it } || term in rest
        fun ownName(term: String) = term in title || keywords.any { term in it }
    }

    // the same setting reached twice (a page listed under two others) is offered once: by its id,
    // or, an entry with none, by its title where it is
    private val entries = entries.filter { it.title.isNotBlank() }
        .distinctBy { if (it.id.isNotEmpty()) it.id else "${it.title}\n${it.path}" }
        .mapIndexed { i, e -> Indexed(e, i) }

    fun find(query: String, limit: Int = LIMIT): List<Found<T>> {
        val terms = norm(query).split(WHITESPACE).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val whole = terms.joinToString(" ")
        return entries.asSequence()
            .mapNotNull { e -> rank(e, terms, whole)?.let { (tier, alias) -> Triple(e, tier, alias) } }
            .sortedWith(compareBy({ it.second }, { it.first.length }, { it.first.order }))
            .take(limit)
            .map { (e, _, alias) -> Found(e.entry, alias?.let { a -> e.entry.keywords.first { norm(it) == a } }) }
            .toList()
    }

    /** [e]'s tier for [terms] ([whole], them joined), and the other name that put it there; null if it is no result. */
    private fun rank(e: Indexed<T>, terms: List<String>, whole: String): Pair<Int, String?>? {
        if (!terms.all(e::has)) return null
        if (e.entry.kind == Kind.VALUE && terms.none(e::ownName)) return null
        val tier = when {
            e.title == whole -> EXACT to null
            e.title.startsWith(whole) -> START to null
            e.keywords.any { it == whole } -> START to whole
            terms.all { it in e.title } -> TITLE to null
            terms.all { t -> e.keywords.any { t in it } } -> KEYWORD to e.keywords.first { k -> terms.any { it in k } }
            else -> ELSEWHERE to null
        }
        return if (e.entry.demoted) minOf(tier.first + 1, ELSEWHERE) to tier.second else tier
    }

    companion object {
        private const val LIMIT = 50
        private const val EXACT = 0
        private const val START = 1
        private const val TITLE = 2
        private const val KEYWORD = 3
        private const val ELSEWHERE = 4

        // \p{Z} for the full-width space a Chinese keyboard types
        private val WHITESPACE = Regex("[\\s\\p{Z}]+")

        /** [s] as it is compared: full-width letters and digits as half-width (NFKC), lower case in no locale's way. */
        fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

        /**
         * Whether a choice called [label] is worth a result of its own: not one Han character, a word
         * of two Latin letters or a number (关, 高, Up, 1.5), which alone would find a choice on every
         * list; 18 键 is one.
         */
        fun isWorthAValue(label: String): Boolean {
            val chars = label.codePoints().filter { !Character.isWhitespace(it) && !Character.isSpaceChar(it) }.toArray()
            val letters = chars.filter { Character.isLetter(it) }
            return when {
                letters.isEmpty() -> false
                chars.size == 1 -> false
                letters.size == chars.size && letters.size <= 2 && letters.none { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN } -> false
                else -> true
            }
        }
    }
}
