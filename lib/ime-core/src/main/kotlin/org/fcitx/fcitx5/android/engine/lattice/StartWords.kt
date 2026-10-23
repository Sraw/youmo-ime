/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

/**
 * A syllable trie of words beside the user's: node [root] the root, each node's children in a run
 * from [firstChild], sorted by the [syllable] reaching them. Two uses: [UserWords.startWords], the
 * words only imported, walked from the input's start alone (U-101, C19); and [UserWords.mergedWords],
 * the merged dictionaries', walked mid-sentence too.
 */
interface StartWords {
    val root: Int get() = 0

    /** @return the child of [node] reached by [syllable], or -1 */
    fun child(node: Int, syllable: Int): Int

    fun firstChild(node: Int): Int

    fun childCount(node: Int): Int

    /** The syllable that reaches [node]. */
    fun syllable(node: Int): Int

    fun wordCount(node: Int): Int

    fun word(node: Int, index: Int): Int
}
