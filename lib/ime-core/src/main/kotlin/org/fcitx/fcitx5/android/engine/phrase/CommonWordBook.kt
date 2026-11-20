/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import java.io.IOException

/**
 * The common words the pinyin sessions share; keyboard deletions hand the whole list to [save], and a
 * refused [save] takes no effect. Indexed per syllable spelling ([indexFor]), a few kept ([MAX_INDEXES]).
 */
class CommonWordBook(
    words: CommonWords = CommonWords.EMPTY,
    private val save: (CommonWords) -> Unit = {},
) {
    var words: CommonWords = words
        private set

    /** The index for [spelling] (scheme or full pinyin) and [lettered]; only full pinyin's 26 keys key a two-syllable word by initials (elsewhere they spell a syllable) and type lüe as `lue`. */
    fun indexFor(spelling: Any, spell: (Int) -> String?, lettered: Boolean = false): CommonWords.Index {
        val key = spelling to lettered
        indexes[key]?.takeIf { it.of === words }?.let { return it }
        if (indexes.size >= MAX_INDEXES) indexes.clear()
        val full = spelling === Syllables
        val index = words.index(spell, initialsOfTwo = full && !lettered) { if (full && !lettered) CommonWords.fullPinyinAlso(it) else null }
        return index.also { indexes[key] = it }
    }

    private val indexes = HashMap<Pair<Any, Boolean>, CommonWords.Index>()

    /** Replacements [typed] is a key of: as typed on letter keys, `'` only at the word's syllable ends; with [letters], each grouped key any of its letters ([PhraseKeys]). */
    fun typed(
        typed: CharSequence,
        spelling: Any,
        spell: (Int) -> String?,
        letters: NineKeyLetters?,
        ways: (Char) -> List<String>? = { null },
        taken: (String) -> List<String> = { listOf(it) },
    ): List<CommonWords.Found> {
        if (words.isEmpty) return emptyList()
        val index = indexFor(spelling, spell, lettered = letters != null)
        if (letters == null) return index.foundTyped(typed.toString())
        val keys = PhraseKeys.of(typed, letters, ways, taken)?.let(index::keysRead).orEmpty()
        return if (keys.isEmpty()) emptyList() else index.found(keys)
    }

    /** 从常用词中删除. */
    fun remove(word: String, replacement: String) = remove(listOf(word), replacement)

    /** From every word that offered it ([CommonWords.Found.words]). */
    fun remove(texts: Collection<String>, replacement: String) {
        val to = words.without(texts, replacement)
        try {
            save(to)
        } catch (_: IOException) {
            return
        }
        words = to
        indexes.clear()
    }

    companion object {
        // a keyboard or two in use; others rebuilt when switched to
        private const val MAX_INDEXES = 4

        fun none() = CommonWordBook()

        /** [list] with [found]'s replacements first, the same texts further down removed. */
        fun <C> first(found: List<CommonWords.Found>, list: List<C>, text: (C) -> String, asText: (String) -> C): List<C> {
            if (found.isEmpty()) return list
            val texts = found.mapTo(HashSet()) { it.text }
            return found.map { asText(it.text) } + list.filter { text(it) !in texts }
        }
    }
}
