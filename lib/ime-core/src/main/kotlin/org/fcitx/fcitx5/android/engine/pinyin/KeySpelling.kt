/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.reading.Zhuyin

/**
 * How the keys of a [T9Segmenter] spell a syllable: in pinyin's letters (the nine, 14 and 18 keys)
 * or in zhuyin's symbols (注音九键, 大千, [ZhuyinKeys]). The syllables, the fuzzy rules and what a
 * syllable taken off the bar puts in the input stay pinyin's; only the keys a syllable is typed
 * with, and how it shows, are this.
 */
internal sealed class KeySpelling {
    /** [pinyin], a syllable (a fuzzy partner too), in the keys' letters; null for one not spelt so. */
    abstract fun of(pinyin: String): String?

    /** The initial [pinyin] (zh) in the keys' letters; null for one not spelt so (y, w in zhuyin). */
    abstract fun initial(pinyin: String): String?

    /** The syllable [id] in the keys' letters, as shown. */
    abstract fun of(id: Int): String

    /** Whether [c] of the input is a letter typed as itself. */
    abstract fun isFixed(c: Char): Boolean

    /** The letter the fixed [c] ([isFixed]) is. */
    abstract fun letterOf(c: Char): Char

    /** [c] of the input as shown or committed, null for one [NineKeyLetters.asTyped] shows. */
    open fun shown(c: Char): String? = null

    /** Whether [c] of the input ends a syllable as the separator does. */
    open fun separates(c: Char): Boolean = c == SyllableGraph.SEPARATOR

    /** Whether [c], no code of the keys', is read all the same: a letter typed as itself or a tone sent as a code. */
    open fun readsOwn(c: Char): Boolean = false

    /** The initials, pinyin's, the key [code] of [letters] may start a syllable with. */
    open fun initialsOf(letters: NineKeyLetters, code: Char): List<String> = T9Segmenter.initialsOf(letters, code)

    /** [offered], a pinyin initial the bar offers, in the keys' letters; null to show it as a syllable. */
    open fun offeredInitial(offered: String): String? = null

    /** The key an initial the bar offers is taken as ([KeyedSegmenter.keyOf]); null for its letters. */
    open fun keyOf(offered: String): Char? = null

    /** The pinyin letters [c] of [letters]' input may be in a custom phrase's key ([KeyedSegmenter.phraseWays]). */
    open fun phraseWays(letters: NineKeyLetters, c: Char): List<String>? = null

    data object Pinyin : KeySpelling() {
        override fun of(pinyin: String): String = pinyin
        override fun initial(pinyin: String): String = pinyin
        override fun of(id: Int): String = Syllables.spelling(id)
        override fun isFixed(c: Char) = c in T9Segmenter.FIXED
        override fun letterOf(c: Char) = c.lowercaseChar()
    }

    /** Zhuyin: a symbol typed as itself and a tone in the input as [ZhuyinKeys]' codes. */
    data object Bopomofo : KeySpelling() {
        override fun of(pinyin: String): String? = Zhuyin.of(pinyin)

        // zh as ㄓ: the initial of its syllable with u, which every initial has (zhu ㄓㄨ, ju ㄐㄩ)
        override fun initial(pinyin: String): String? = if (pinyin in INITIALS) Zhuyin.of(pinyin + "u")?.take(1) else null
        override fun of(id: Int): String = Zhuyin.of(id) ?: Syllables.spelling(id)
        override fun isFixed(c: Char) = ZhuyinKeys.symbolOf(c) != null
        override fun letterOf(c: Char) = ZhuyinKeys.symbolOf(c) ?: c
        override fun shown(c: Char): String? =
            ZhuyinKeys.symbolOf(c)?.toString() ?: ZhuyinKeys.toneOf(c).takeIf { it > 0 }?.let { ZhuyinKeys.MARKS[it - 1].toString() }
        override fun separates(c: Char) = SyllableGraph.isSeparator(c)

        // the separator is the keys' code: a symbol typed as itself and a tone are not
        override fun readsOwn(c: Char) = isFixed(c) || separates(c) && c != SyllableGraph.SEPARATOR

        // the key's initial symbols as pinyin's initials, what the bar puts in
        override fun initialsOf(letters: NineKeyLetters, code: Char): List<String> =
            (ZhuyinKeys.symbolOf(code)?.toString() ?: letters.letters(code)).mapNotNull { initialOf[it] }

        // m, n and r (ㄇ ㄋ ㄖ) as initials only, never as 呣 嗯 儿's syllables
        override fun offeredInitial(offered: String): String? = initial(offered)

        override fun keyOf(offered: String): Char? = initial(offered)?.singleOrNull()?.let(ZhuyinKeys::symbolCode)

        override fun phraseWays(letters: NineKeyLetters, c: Char): List<String>? = when {
            ZhuyinKeys.toneOf(c) > 0 -> emptyList()
            else -> (ZhuyinKeys.symbolOf(c)?.toString() ?: letters.letters(c).takeIf { letters.isCode(c) })?.flatMap { ZhuyinKeys.PINYIN_WAYS.getValue(it) }?.distinct()
        }

        private val INITIALS = setOf("b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x", "zh", "ch", "sh", "r", "z", "c", "s")

        /** The pinyin initial each initial symbol is. */
        val initialOf: Map<Char, String> = INITIALS.associateBy { initial(it)!!.single() }
    }

    companion object {
        /** The spelling of the keys [letters]: zhuyin's for [ZhuyinKeys]' keyboards. */
        fun of(letters: NineKeyLetters): KeySpelling = if (letters == ZhuyinKeys.NINE || letters == ZhuyinKeys.DACHEN) Bopomofo else Pinyin
    }
}
