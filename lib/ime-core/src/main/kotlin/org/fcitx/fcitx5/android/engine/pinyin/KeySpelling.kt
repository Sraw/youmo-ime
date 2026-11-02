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
        override fun separates(c: Char) = c == SyllableGraph.SEPARATOR || ZhuyinKeys.toneOf(c) > 0

        private val INITIALS = setOf("b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x", "zh", "ch", "sh", "r", "z", "c", "s")

        /** The pinyin initial each initial symbol is. */
        val initialOf: Map<Char, String> = INITIALS.associateBy { initial(it)!!.single() }
    }

    companion object {
        /** The spelling of the keys [letters]: zhuyin's for [ZhuyinKeys]' keyboards. */
        fun of(letters: NineKeyLetters): KeySpelling = if (letters == ZhuyinKeys.NINE || letters == ZhuyinKeys.DACHEN) Bopomofo else Pinyin
    }
}
