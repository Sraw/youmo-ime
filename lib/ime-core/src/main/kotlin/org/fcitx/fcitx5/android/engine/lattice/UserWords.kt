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

    /** Whether [word] is blocked in some reading: not predicted, as a prediction has none. */
    fun blockedAnyhow(word: Int): Boolean = false

    /** Each word the user typed after [prev]: what they may type there again (联想). */
    fun forEachAfter(prev: Int, visit: (Int) -> Unit) {}

    /** The syllables of [word] as the user typed it, of this trie's or the dictionary's; null if never. */
    fun reading(word: Int): IntArray? = null

    /** The chance the user types [word] after [prev], from what they typed: 0 for a word never typed. */
    fun probability(prev: Int, word: Int): Float = 0f
}
