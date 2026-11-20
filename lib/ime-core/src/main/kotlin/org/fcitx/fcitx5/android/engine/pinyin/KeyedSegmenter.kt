/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

/** A [Segmenter] of grouped keys: input is key codes; syllables are shown spelt and taken off a bar, a taken one standing in the input as its letters and a separator. */
interface KeyedSegmenter : Segmenter {
    val letters: NineKeyLetters

    override val typesLetters: Boolean get() = false

    /** A key code, or a letter typed as itself where there are such. */
    fun isKey(c: Char): Boolean

    /** Initials [code] may start a syllable with, for the bar's 简拼 (自然码's v is zh). */
    fun initialsOf(code: Char): List<String>

    /** A code as its key's first letter ([NineKeyLetters.asTyped]). */
    fun asTyped(text: CharSequence): String = letters.asTyped(text.toString())

    /** How [syllable] shows when [keys] keys do not yet type it whole: that many letters on nine keys, its initial in 双拼. */
    fun started(syllable: Int, keys: Int): String

    /** A bar-taken [spelling] as a custom phrase key: each way it is typed in letters. */
    fun takenAs(spelling: String): List<String> = listOf(spelling)

    /** Pinyin, or symbols on zhuyin keyboards. */
    fun spelt(syllable: Int): String = Syllables.spelling(syllable)

    /** How a bar-offered syllable or initial shows: as is, or in zhuyin symbols (ㄋㄧ). */
    fun shownOffered(offered: String): String = offered

    /** The key a bar-offered initial is taken as: on zhuyin its symbol (ㄓ for zh), from which a final continues and which alone reads its whole syllable; null: taken as letters and a separator (简拼). */
    fun keyOf(offered: String): Char? = null

    /** The separator, or a tone on zhuyin keyboards. */
    fun separates(c: Char): Boolean = c == SyllableGraph.SEPARATOR

    /** How [syllable] read off [text] (its keys, a slipped one as meant) shows as the keys pressed; null: as [asTyped]. Only grouped 双拼 says. */
    fun keysShown(text: String, syllable: Int): String? = null

    /** Only grouped 双拼 says. */
    fun endsSyllable(lead: String, final: Char): Boolean = false

    /** Zhuyin keys' pinyin letters for a phrase key (PhraseKeys.of's ways); null otherwise. */
    fun phraseWays(c: Char): List<String>? = null

    /** What a space after a toneless syllable types (大千's first tone); null where space picks the first candidate. */
    val spaceTone: Char? get() = null

    /** Whether a space after [before] is [spaceTone]: after a key, a syllable without a tone yet. */
    fun tonesBySpace(before: CharSequence): Boolean = spaceTone != null && before.isNotEmpty() && isKey(before.last())

    /** Whether a character read as a syllable may take a typed tone (zhuyin): PinyinDecoder's toneAllows; null: not asked. */
    val toneAllows: ((codePoint: Int, syllable: Int, tone: Int) -> Boolean)? get() = null
}
