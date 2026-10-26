/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

/**
 * A [Segmenter] of keys that each type a group of [letters] (the nine keys, the 14 keys, 双拼's
 * 18 and nine keys): its input is the keys' codes, the syllables shown spelt and taken one by one
 * off a bar, a syllable taken standing in the input as its letters and a separator.
 */
interface KeyedSegmenter : Segmenter {
    val letters: NineKeyLetters

    override val typesLetters: Boolean get() = false

    /** Whether [c] is a key typed: one of the keys' codes, or a letter typed as itself where there are such. */
    fun isKey(c: Char): Boolean

    /**
     * The initials the key [code] (or a letter typed as itself) may start a syllable with, for the
     * bar's 简拼: its letters on the nine keys, the scheme's initials on its letters for 双拼
     * (自然码's v is zh).
     */
    fun initialsOf(code: Char): List<String>

    /** [text], of the input, as it is shown or committed: a code as its key's first letter ([NineKeyLetters.asTyped]). */
    fun asTyped(text: CharSequence): String = letters.asTyped(text.toString())

    /** How [syllable] shows read off [keys] keys that do not type it whole yet: as many letters on the nine keys, its initial in 双拼. */
    fun started(syllable: Int, keys: Int): String
}
