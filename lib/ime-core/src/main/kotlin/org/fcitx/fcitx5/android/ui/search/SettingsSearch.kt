/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.search

import java.text.Normalizer
import java.util.Locale

/**
 * Settings by every query word in title, [Entry.keywords], summary or page path, any case or width. A list choice
 * ([Kind.VALUE]) only by its own name (键盘高度 alone lists no heights). [rank]: exact title, title prefix, all words
 * in the title, in another name, the rest; then shorter title, then page order. [T]: what opening a result needs.
 */
class SettingsSearch<T>(entries: List<Entry<T>>) {

    /** A page, a setting on it, one choice of a list, a command a key's swipe can run. */
    enum class Kind { PAGE, SETTING, VALUE, COMMAND }

    /** Pages outermost first; [id] the same in every language and version, results kept once by it; [demoted] a step lower (the developer's page). */
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

    /** [alias]: the other name it was found by, said under it. */
    data class Found<T>(val entry: Entry<T>, val alias: String?) {
        val target: T get() = entry.target
    }

    /** Normalised once: [find] runs on every key typed. */
    private class Indexed<T>(val entry: Entry<T>, val order: Int) {
        val title = norm(entry.title)
        val keywords = entry.keywords.map(::norm).filter { it.isNotEmpty() }
        val rest = norm(listOf(entry.summary, entry.path.joinToString(" ")).joinToString("\n"))
        val length = entry.title.codePointCount(0, entry.title.length)

        fun has(term: String) = term in title || keywords.any { term in it } || term in rest
        fun ownName(term: String) = term in title || keywords.any { term in it }
    }

    // a setting reached twice (a page under two others) offered once: by its id, else by its title where it is
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

    /** Null if it is no result. */
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

        /** NFKC (full-width as half-width), lower case in no locale's way. */
        fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

        /** Not one Han character, two Latin letters or a number (关, 高, Up, 1.5), which would find a choice on every list. */
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
