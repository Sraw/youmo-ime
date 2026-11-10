/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

/** Turns what was typed into the syllables it may stand for, the same for every way of typing pinyin. */
fun interface Segmenter {
    fun segment(input: String): SyllableGraph

    /**
     * [input] as [segment] has it, [taps] where across its key each place was tapped (NaN for no
     * place; null for none at all): weighed where a key types two letters, the grouped keyboards'
     * ([T9Segmenter]). Any other way of typing has no such keys.
     */
    fun segment(input: String, taps: FloatArray?): SyllableGraph = segment(input)

    /** Whether [c] is a key of this way of typing: what a keyboard hands to the engine. */
    fun reads(c: Char): Boolean = c in 'a'..'z'

    /**
     * Whether [c], a key this [reads], is read after [before] (the input not yet taken, up to the
     * cursor): false where it can only be punctuation there (双拼's `;`, a final, with no initial
     * before it).
     */
    fun readsAfter(before: String, c: Char): Boolean = true

    /**
     * Whether the keys are the letters of the pinyin, as typed; false for the nine keys, whose
     * input is digits, the syllables shown spelt and taken one by one ([T9Segmenter]).
     */
    val typesLetters: Boolean get() = true

    /**
     * Whether the keys spell full pinyin, so the built-in phrases' keys (`rq`, `sj`, `xq`) are no
     * syllable's: false in 双拼, where `sj` and `xq` spell syllables (san, xiu in 自然码) whose words they would push down.
     */
    val spellsFullPinyin: Boolean get() = true

    /**
     * How [syllable] is typed in letters, as a common word's key has it (CommonWords): its pinyin;
     * 双拼's two keys, as the scheme teaches them. Null where it cannot be typed so.
     */
    fun typedAs(syllable: Int): String? = Syllables.spelling(syllable)

    /** What [typedAs] differs by among segmenters: two alike type alike (an index of the keys kept for it). */
    val typing: Any get() = Syllables
}
