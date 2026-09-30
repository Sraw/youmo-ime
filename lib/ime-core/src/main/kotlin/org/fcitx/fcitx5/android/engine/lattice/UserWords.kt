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
}
