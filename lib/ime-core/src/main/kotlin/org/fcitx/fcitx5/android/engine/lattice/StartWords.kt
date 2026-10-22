/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

/**
 * The words offered only as the word the input starts with, never inside a sentence nor after
 * another word (U-101, C19: those only imported), by the syllables they read: node [root] the
 * root, each node's children in a run from [firstChild], sorted by the [syllable] reaching them.
 * The decoder walks it from the input's start alone ([UserWords.startWords]).
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
