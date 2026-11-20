/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

/**
 * A syllable trie beside the user's: node [root], children in runs from [firstChild] sorted by [syllable].
 * Used for [UserWords.startWords] (imported-only words, walked from the input's start only) and
 * [UserWords.mergedWords] (merged dictionaries, walked mid-sentence too).
 */
interface StartWords {
    val root: Int get() = 0

    /** @return the child of [node] reached by [syllable], or -1 */
    fun child(node: Int, syllable: Int): Int

    fun firstChild(node: Int): Int

    fun childCount(node: Int): Int

    fun syllable(node: Int): Int

    fun wordCount(node: Int): Int

    fun word(node: Int, index: Int): Int
}
