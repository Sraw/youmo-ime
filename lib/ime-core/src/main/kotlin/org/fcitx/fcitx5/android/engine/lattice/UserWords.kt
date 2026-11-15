/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

/**
 * Words the user taught the engine, beside the dictionary it ships: a trie over syllable ids like
 * [org.fcitx.fcitx5.android.engine.data.PinyinDictionary], whose words have ids from the
 * vocabulary's size up, so they share the decoder's word ids with the dictionary's.
 */
interface UserWords {
    val root: Int get() = 0

    /** @return the child of [node] reached by [syllable], or -1 */
    fun child(node: Int, syllable: Int): Int

    fun childCount(node: Int): Int

    fun wordCount(node: Int): Int

    fun word(node: Int, index: Int): Int

    /** The text of [word], one of this trie's. */
    fun text(word: Int): String

    /**
     * Whether the user blocked [word] as read at [node], the dictionary's node for a word of the
     * dictionary's (one of its readings: 行 blocked as hang is still xing), -1 for one of this
     * trie's (which has one reading): it is then never offered so read.
     */
    fun blocked(word: Int, node: Int): Boolean = false

    /**
     * Whether [word] is offered only as a word the input starts with, never inside a sentence nor
     * after another word: one only imported (U-101, C19).
     */
    fun startOnly(word: Int): Boolean = false

    /**
     * The words only imported, by their syllables: walked from where the input starts alone, so
     * none is inside a sentence nor after another word (U-101, C19), and apart from this trie, so
     * nothing else walks them (handwriting's sentences). Null for none.
     */
    val startWords: StartWords? get() = null

    /**
     * The dictionaries the user merged into the base one, by their syllables, words best first:
     * walked beside this trie everywhere, as words of the user's dictionaries always were, but read
     * from their compiled file in place (DictionaryFile), never put in this trie. Null for none.
     */
    val mergedWords: StartWords? get() = null

    /** Whether [word] is one of [mergedWords]'. */
    fun isMerged(word: Int): Boolean = false

    /** Each id of [mergedWords] whose text is [text]. */
    fun forEachMergedOfText(text: String, visit: (Int) -> Unit) {}

    /**
     * Whether any of [mergedWords] is the user's choice (owned, added by hand): if so, a walker that
     * cuts a node's words at a limit looks on past it for [chosen] ones (review S5).
     */
    fun anyMergedChosen(): Boolean = false

    /**
     * Whether any of [mergedWords] has counts of the user's: if not, a glide takes a merged node's
     * first word alone though it scores learned ones. True unless the model keeps track.
     */
    fun anyMergedCounted(): Boolean = true

    /** Whether [word] is the user's choice: owned or added by hand. */
    fun chosen(word: Int): Boolean = false

    /** Whether [word] is blocked in some reading: not predicted, as a prediction has none. */
    fun blockedAnyhow(word: Int): Boolean = false

    /** Each word the user typed after [prev]: what they may type there again (联想). */
    fun forEachAfter(prev: Int, visit: (Int) -> Unit) {}

    /** Whether the user said never to predict [next] after [prev] (U-72 不再联想), by their texts. */
    fun notPredicted(prev: String, next: String): Boolean = false

    /**
     * Each word put in right after [prev], by any input method, and how often (U-71): what 联想
     * offers after it once often enough ([Predictor.PAIRS]). Not the decoder's pairs, which leave
     * out what was put in by its text alone (a table's, handwriting's, 联想's words).
     */
    fun forEachPredicted(prev: Int, visit: (word: Int, count: Float) -> Unit) {}

    /**
     * The syllables of [word] as the user typed it, of this trie's or the dictionary's, while they
     * still have it as their choice (typed, or added by hand); null if never, only imported, or
     * forgotten since.
     */
    fun reading(word: Int): IntArray? = null

    /**
     * The model's word [word] is to the model, as context and scored: the model's of the same text
     * for one of the user's they chose at a reading of their own; else [word] itself.
     */
    fun modelWord(word: Int): Int = word

    /** How many times the user committed [word]: 0 for a word never typed. */
    fun count(word: Int): Float = 0f

    /**
     * Whether [word] is one the user typed or chose: counted ([count]), their choice ([chosen]), or
     * the dictionary's own word they added by hand.
     * The only words read through a 双拼 key alone where it and the next make a syllable (简拼 in the
     * middle): every key there stands for a score of syllables, and with all the dictionary's words
     * behind them a key of full code took twice the time on a phone and the words meant were
     * crowded out. A count fades with the typing days ([org.fcitx.fcitx5.android.engine.user.LearnState.WINDOW]);
     * a choice does not.
     */
    fun typed(word: Int): Boolean = count(word) > 0f || chosen(word)

    /**
     * Whether the dictionary's [node] may have a [typed] word at it or below: false only where none
     * is, so a walk over a key read alone leaves the node be. True unless the model keeps track.
     */
    fun typedBelow(node: Int): Boolean = true

    /** [typedBelow] for a node of [mergedWords]. */
    fun mergedTypedBelow(node: Int): Boolean = true

    /** The chance the user types [word] after [prev], from what they typed: 0 for a word never typed. */
    fun probability(prev: Int, word: Int): Float = 0f
}
