/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

/**
 * The user's words beside the shipped dictionary: a syllable trie like
 * [org.fcitx.fcitx5.android.engine.data.PinyinDictionary], with ids from the vocabulary's size up so they
 * share the decoder's id space.
 */
interface UserWords {
    val root: Int get() = 0

    /** @return the child of [node] reached by [syllable], or -1 */
    fun child(node: Int, syllable: Int): Int

    fun childCount(node: Int): Int

    fun wordCount(node: Int): Int

    fun word(node: Int, index: Int): Int

    /** Of this trie's words. */
    fun text(word: Int): String

    /**
     * Whether [word] is blocked as read at [node]: the dictionary's node for a dictionary word (per reading:
     * 行 blocked as hang is still xing), -1 for one of this trie's (one reading).
     */
    fun blocked(word: Int, node: Int): Boolean = false

    /** Offered only as the word the input starts with, never mid-sentence: imported-only. */
    fun startOnly(word: Int): Boolean = false

    /**
     * Imported-only words by syllables, walked from the input's start only and apart from this trie, so
     * nothing else walks them (handwriting's sentences). Null for none.
     */
    val startWords: StartWords? get() = null

    /**
     * The user's merged dictionaries by syllables, words best first: walked beside this trie everywhere,
     * read in place from their compiled file (DictionaryFile), never put in this trie. Null for none.
     */
    val mergedWords: StartWords? get() = null

    fun isMerged(word: Int): Boolean = false

    fun forEachMergedOfText(text: String, visit: (Int) -> Unit) {}

    /** Any merged word is the user's choice: then a walker cutting a node's words at a limit looks past it for [chosen] ones. */
    fun anyMergedChosen(): Boolean = false

    /** Any merged word has user counts: if not, a glide takes a merged node's first word alone. True unless tracked. */
    fun anyMergedCounted(): Boolean = true

    /** Owned or added by hand. */
    fun chosen(word: Int): Boolean = false

    /** Blocked in some reading: not predicted, as a prediction has no reading. */
    fun blockedAnyhow(word: Int): Boolean = false

    /** Words the user typed after [prev] (联想). */
    fun forEachAfter(prev: Int, visit: (Int) -> Unit) {}

    /** 不再联想, by texts. */
    fun notPredicted(prev: String, next: String): Boolean = false

    /**
     * Each word put in right after [prev] by any input method, and how often, for 联想 once often enough
     * ([Predictor.PAIRS]). Not the decoder's pairs, which omit text-only commits (tables, handwriting, 联想).
     */
    fun forEachPredicted(prev: Int, visit: (word: Int, count: Float) -> Unit) {}

    /** [word]'s syllables as the user typed it while still their choice (typed or added); null if never, imported-only, or forgotten. */
    fun reading(word: Int): IntArray? = null

    /** The model word [word] is as context and when scored: the model's word of its text for a user word chosen at their own reading; else itself. */
    fun modelWord(word: Int): Int = word

    /** 0 for never typed. */
    fun count(word: Int): Float = 0f

    /**
     * Typed or chosen: counted ([count]), [chosen], or a dictionary word added by hand. The only words read
     * through a lone 双拼 key mid-input (简拼): each such key stands for a score of syllables, and with the
     * whole dictionary behind them a key took twice as long on a phone and the meant words were crowded
     * out. Counts fade with typing days ([org.fcitx.fcitx5.android.engine.learn.LearnState.WINDOW]); choices don't.
     */
    fun typed(word: Int): Boolean = count(word) > 0f || chosen(word)

    /** False only where no [typed] word is at or below [node], so a lone-key walk skips it. True unless tracked. */
    fun typedBelow(node: Int): Boolean = true

    /** The first node from [from] on that is [typedBelow]; past the last (or -1) for none. [from] unless tracked. */
    fun nextTypedBelow(from: Int): Int = from

    fun mergedTypedBelow(node: Int): Boolean = true

    /** From what the user typed; 0 for a word never typed. */
    fun probability(prev: Int, word: Int): Float = 0f
}
