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

    /**
     * A syllable or initial taken off the bar, [spelling] in the input, as a custom phrase's key has
     * it (PhraseKeys): each way it is typed in letters. Its own letters on full pinyin's keyboards.
     */
    fun takenAs(spelling: String): List<String> = listOf(spelling)

    /** How [syllable] read whole shows: its pinyin, its symbols on the zhuyin keyboards. */
    fun spelt(syllable: Int): String = Syllables.spelling(syllable)

    /** Whether [c] of the input ends a syllable: the separator, a tone on the zhuyin keyboards. */
    fun separates(c: Char): Boolean = c == SyllableGraph.SEPARATOR

    /** The pinyin letters [c] of the input may be in a custom phrase's key, null for none of the kind (see PhraseKeys.of's ways): zhuyin's keys'. */
    fun phraseWays(c: Char): List<String>? = null

    /** What a space after a syllable with no tone puts in, null where a space picks the first candidate: 大千's first tone. */
    val spaceTone: Char? get() = null

    /** Whether a space after [before], the input not yet picked up to the cursor, is [spaceTone]: after a key, a syllable with no tone yet (design 051 Q3 A). */
    fun tonesBySpace(before: CharSequence): Boolean = spaceTone != null && before.isNotEmpty() && isKey(before.last())

    /** Whether a character read as a syllable may have a tone typed after it (zhuyin's keys'): PinyinDecoder's toneAllows; null, none is asked. */
    val toneAllows: ((codePoint: Int, syllable: Int, tone: Int) -> Boolean)? get() = null
}
