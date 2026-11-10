/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import java.io.IOException

/**
 * The common words ([CommonWords]) the pinyin sessions share, and the user's deleting one of their
 * replacements from the keyboard, handed to [save] as the whole list, as the file keeps them; a
 * [save] refused (an [IOException]) takes no effect, as [PhraseBook]'s.
 *
 * Indexed by the way each session spells a syllable ([indexFor]): one index a spelling, made anew
 * after a change.
 */
class CommonWordBook(
    words: CommonWords = CommonWords.EMPTY,
    private val save: (CommonWords) -> Unit = {},
) {
    var words: CommonWords = words
        private set

    /** [CommonWords.index] of [words] as [spell] spells, kept for [spelling] (a key naming the way, 双拼's scheme or full pinyin). */
    fun indexFor(spelling: Any, spell: (Int) -> String?): CommonWords.Index {
        indexes[spelling]?.takeIf { it.of === words }?.let { return it }
        return words.index(spell).also { indexes[spelling] = it }
    }

    private val indexes = HashMap<Any, CommonWords.Index>()

    /**
     * The replacements [typed], what is left of a pinyin input, is a key of, as [indexFor] spells:
     * as typed where the keys type letters (a separator left out); with [letters], keys of letter
     * groups, each key any of its letters, as the custom phrases read them ([PhraseKeys], [PhraseBook.typed]).
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
        val index = indexFor(spelling, spell)
        val keys = if (letters == null) {
            listOf(typed.filter { it != SyllableGraph.SEPARATOR }.toString()).filter { it in index }
        } else {
            PhraseKeys.of(typed, letters, ways, taken)?.let(index::keysRead).orEmpty()
        }
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
    }

    companion object {
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
