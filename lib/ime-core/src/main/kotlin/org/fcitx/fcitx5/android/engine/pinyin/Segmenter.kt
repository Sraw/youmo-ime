/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

/** Turns typed input into the syllables it may stand for, for every pinyin input style. */
fun interface Segmenter {
    fun segment(input: String): SyllableGraph

    /** [taps]: where across its key each place was tapped (NaN none; null for all), weighed on two-letter grouped keys. */
    fun segment(input: String, taps: FloatArray?): SyllableGraph = segment(input)

    /** Plus [touches] (each place's tapped key and neighbours), for reading slips; without slips as [segment]. */
    fun segment(input: String, taps: FloatArray?, touches: Array<Touch?>?): SyllableGraph = segment(input, taps)

    /** What a keyboard hands to the engine for this style. */
    fun reads(c: Char): Boolean = c in 'a'..'z'

    /** False where [c] can only be punctuation after [before] (双拼's `;` final with no initial). */
    fun readsAfter(before: String, c: Char): Boolean = true

    /** False for grouped keys, whose input is codes with syllables taken off a bar ([T9Segmenter]). */
    val typesLetters: Boolean get() = true

    /** False in 双拼, where built-in phrase keys (`sj`, `xq`) spell syllables whose words they would push down. */
    val spellsFullPinyin: Boolean get() = true

    /** How [syllable] is typed in letters, for common-word keys: pinyin, or 双拼's taught keys; null if it cannot be. */
    fun typedAs(syllable: Int): String? = Syllables.spelling(syllable)

    /** Equal values type alike (keys a common-word index). */
    val typing: Any get() = Syllables
}
