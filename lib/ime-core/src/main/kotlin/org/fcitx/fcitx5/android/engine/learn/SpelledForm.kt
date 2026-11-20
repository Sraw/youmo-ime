/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters

/**
 * How a code spells a word of its keyboard (完整拼音和简拼), told by key count alone, so a slip or fuzzy
 * reading of the same length counts as typed. Only words of two+ syllables: a lone syllable's initial is
 * half of it, too many words to lift. Within one keyboard only: full pinyin's uses never reach 双拼 or the nine keys.
 */
enum class SpelledForm {
    /** Every letter (fenghua, nine-key 3364482) or, for 双拼, two keys per syllable. */
    FULL,
    /** A key a syllable (fh, 34). */
    INITIALS,
    /** Neither (dianl, zhd). */
    PART,
    ;

    companion object {
        /** How [keys] under [kind] spell [reading] (space-separated syllables); null for none: one syllable, a kind without readings, fewer keys than syllables. */
        fun of(kind: String, keys: String, reading: String): SpelledForm? {
            // zhuyin keys spell no pinyin letters: its keyboards link no spellings (kept simple on purpose)
            if (Kinds.byText(kind) || Kinds.isZhuyin(kind) || reading.isEmpty()) return null
            val syllables = reading.split(' ')
            return of(Kinds.isShuangpin(kind), keys.count { it != '\'' }, syllables.size, syllables.sumOf { it.length })
        }

        /**
         * [of] by counts, for a kind with readings ([Kinds.byText] false): [n] keys (no separator) spelling a
         * reading of [syllables] syllables and [letters] letters; [shuangpin], two keys a syllable. Each level asks
         * this of every word under its first key, so no reading is split there.
         */
        fun of(shuangpin: Boolean, n: Int, syllables: Int, letters: Int): SpelledForm? {
            if (syllables < 2) return null
            // "f" never reads feng hua: no spelling, and no reason to read the level
            if (n < syllables) return null
            val full = if (shuangpin) 2 * syllables else letters
            return when (n) {
                full -> FULL
                syllables -> INITIALS
                else -> PART
            }
        }

        /**
         * Whether [keys] on [keyboard] ([Kinds.keyboard]) can start each syllable of [reading] in turn (fengh,
         * feng'h, 33644 for feng hua): a cheap check before walking a word into a level; the walk still decides.
         * 双拼 is not checked. Fuzzy readings and slips are not walked in this way; the decoder's own lists have them.
         */
        fun spells(keyboard: String, keys: String, reading: String): Boolean {
            if (Kinds.isShuangpin(keyboard)) return true
            val typed = keys.filter { it != '\'' }
            return spells(typed, Array(typed.length) { nineKeyLetters(keyboard, typed[it]) }, reading.split(' '))
        }

        /** [spells] off 双拼, parts precomputed for the many words under a key: [typed] keys without separator, [letters] each one's nine-key letters ([nineKeyLetters], empty off them), [syllables] the reading's. */
        fun spells(typed: String, letters: Array<String>, syllables: List<String>): Boolean {
            fun same(i: Int, c: Char) = typed[i] == c || c in letters[i]
            fun from(i: Int, j: Int): Boolean {
                if (j == syllables.size) return i == typed.length
                val syl = syllables[j]
                var k = 0
                while (k < syl.length && i + k < typed.length && same(i + k, syl[k])) {
                    k++
                    if (from(i + k, j + 1)) return true
                }
                return false
            }
            return from(0, 0)
        }

        /** The first key a code of [reading] is filed under: on nine-key and grouped keyboards the reading's first letter's key, so 连按 and digits find each other; else the code's first key. */
        fun filedUnder(keyboard: String, keys: String, reading: String): Char? =
            if (keyboard.startsWith(NINE_KEYS) || keyboard.startsWith(GROUPED)) reading.firstOrNull() else keys.firstOrNull { it != '\'' }

        /** The letters [code] types on [keyboard]'s nine keys or grouped keys ([NineKeyLetters.filedAs]); not grouped 双拼, whose rows are filed by the code itself. */
        fun nineKeyLetters(keyboard: String, code: Char): String = when {
            keyboard.startsWith(GROUPED) -> Kinds.keysTag(keyboard)?.let { NineKeyLetters.filedAs(it) }?.letters(code).orEmpty()
            !keyboard.startsWith(NINE_KEYS) || code !in '2'..'9' -> ""
            else -> keyboard.removePrefix(NINE_KEYS).split(',').getOrElse(code - '2') { "" }
        }

        private val NINE_KEYS = Kinds.nineKeys("")
        // the 14 keys' alone: a grouped 双拼 key is filed by its code
        private val GROUPED = Kinds.keys("")
    }
}
