/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import java.io.IOException

/**
 * The common words ([CommonWords]) the pinyin sessions share, and the user's deleting one of their
 * replacements from the keyboard, handed to [save] as the whole list, as the file keeps them; a
 * [save] refused (an [IOException]) takes no effect, as [PhraseBook]'s.
 *
 * Indexed by the way each session spells a syllable ([indexFor]): one index a spelling, made anew
 * after a change, a few kept ([MAX_INDEXES]: the keyboards switched between).
 */
class CommonWordBook(
    words: CommonWords = CommonWords.EMPTY,
    private val save: (CommonWords) -> Unit = {},
) {
    var words: CommonWords = words
        private set

    /**
     * [CommonWords.index] of [words] as [spell] spells, kept for [spelling] (a key naming the way,
     * 双拼's scheme or full pinyin, [Syllables]) and [lettered] (keys of letter groups typing it).
     * Full pinyin's 26 keys alone key a word of two by its initials: elsewhere they are a syllable's
     * keys (双拼's `wm` is wen, the nine keys' 96 wo); and they alone type lüe `lue` as well.
     */
    fun indexFor(spelling: Any, spell: (Int) -> String?, lettered: Boolean = false): CommonWords.Index {
        val key = spelling to lettered
        indexes[key]?.takeIf { it.of === words }?.let { return it }
        if (indexes.size >= MAX_INDEXES) indexes.clear()
        val full = spelling === Syllables
        val index = words.index(spell, initialsOfTwo = full && !lettered) { if (full && !lettered) CommonWords.fullPinyinAlso(it) else null }
        return index.also { indexes[key] = it }
    }

    private val indexes = HashMap<Pair<Any, Boolean>, CommonWords.Index>()

    /**
     * The replacements [typed], what is left of a pinyin input, is a key of, as [indexFor] spells:
     * as typed where the keys type letters, a separator only where a syllable of the word ends
     * ([CommonWords.Index.foundTyped]); with [letters], keys of letter groups, each key any of its
     * letters, as the custom phrases read them ([PhraseKeys], [PhraseBook.typed]).
     */
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

    /** [replacement] no longer one of [word]'s (从常用词中删除). */
    fun remove(word: String, replacement: String) {
        val to = words.without(word, replacement)
        try {
            save(to)
        } catch (_: IOException) {
            return
        }
        words = to
        indexes.clear()
    }

    companion object {
        // the ways of spelling indexed at once: a keyboard or two in use, the rest made again if switched to
        private const val MAX_INDEXES = 4

        /** None, as a fresh install has (U-93's 借手机 too). */
        fun none() = CommonWordBook()

        /**
         * [list] with [found]'s replacements first, each [asText], one of their text further down
         * taken out: it is them (060-common-words §4).
         */
        fun <C> first(found: List<CommonWords.Found>, list: List<C>, text: (C) -> String, asText: (String) -> C): List<C> {
            if (found.isEmpty()) return list
            val texts = found.mapTo(HashSet()) { it.text }
            return found.map { asText(it.text) } + list.filter { text(it) !in texts }
        }
    }
}
