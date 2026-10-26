/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters

/**
 * How a code spells a word of its keyboard (U-20, D32 B; docs/pinyin.md 完整拼音和简拼): told by
 * the number of keys alone, so a slip or a fuzzy reading of the same length counts as typed. Only
 * a word of two syllables or more is any of them: a syllable alone's initial is half of it, too
 * many words to lift. Within one keyboard only: full pinyin's uses never reach 双拼 or the nine keys.
 */
enum class SpelledForm {
    /** Every letter (fenghua, the nine keys' 3364482) or, 双拼, two keys a syllable. */
    FULL,
    /** A key a syllable (fh, 34). */
    INITIALS,
    /** Neither (dianl, zhd). */
    PART,
    ;

    companion object {
        /** How [keys] under [kind] spell [reading] (syllables apart by spaces); null where none is: a word of one syllable, a kind of no readings, fewer keys than syllables (no spelling of it). */
        fun of(kind: String, keys: String, reading: String): SpelledForm? {
            if (Kinds.byText(kind) || reading.isEmpty()) return null
            val syllables = reading.split(' ')
            if (syllables.size < 2) return null
            val n = keys.count { it != '\'' }
            // "f" never reads feng hua: no spelling, nor a reason to read the level (R14 F3741)
            if (n < syllables.size) return null
            val full = if (Kinds.isShuangpin(kind)) 2 * syllables.size else syllables.sumOf { it.length }
            return when (n) {
                full -> FULL
                syllables.size -> INITIALS
                else -> PART
            }
        }

        /**
         * Whether [keys] on [keyboard] ([Kinds.keyboard]) can be the start of each syllable of
         * [reading] in turn (fengh, feng'h, 33644 for feng hua): a cheap look before a word is
         * walked into a level (R14 F3733), the walk still the judge. 双拼 is not looked at. A
         * fuzzy reading or a slip is not walked in so; the decoder's own lists still have them.
         */
        fun spells(keyboard: String, keys: String, reading: String): Boolean {
            if (Kinds.isShuangpin(keyboard)) return true
            val typed = keys.filter { it != '\'' }
            val syllables = reading.split(' ')
            fun same(key: Char, c: Char) = key == c || c in nineKeyLetters(keyboard, key)
            // from key i on, syllable j on: each syllable at least its first letter
            fun from(i: Int, j: Int): Boolean {
                if (j == syllables.size) return i == typed.length
                val syl = syllables[j]
                var k = 0
                while (k < syl.length && i + k < typed.length && same(typed[i + k], syl[k])) {
                    k++
                    if (from(i + k, j + 1)) return true
                }
                return false
            }
            return from(0, 0)
        }

        /**
         * The first key a code of [reading] on [keyboard] ([Kinds.keyboard]) is filed under, to be
         * found at a key: the nine keys' (digits, or 连按's letters) by the reading's first letter,
         * so that 连按 and digits find each other (R14 F3731), and a grouped keyboard's so, its key
         * found by its letters as a digit is ([nineKeyLetters]); others by the code's first key.
         */
        fun filedUnder(keyboard: String, keys: String, reading: String): Char? =
            if (keyboard.startsWith(NINE_KEYS) || keyboard.startsWith(GROUPED)) reading.firstOrNull() else keys.firstOrNull { it != '\'' }

        /**
         * The letters [code] types on the nine keys of [keyboard], whose layout's tag ([Kinds.nineKeys]) has
         * each key's letters apart by commas; or the 14 keys' code ([Kinds.keys], BN's: bn) as that keyboard
         * has it ([NineKeyLetters.grouped]), whose codes the tag does not say. Not grouped 双拼's: its rows
         * are filed by the code itself ([filedUnder]), so its letters would look where none is.
         */
        fun nineKeyLetters(keyboard: String, code: Char): String = when {
            keyboard.startsWith(GROUPED) -> Kinds.keysTag(keyboard)?.let { NineKeyLetters.grouped(it) }?.letters(code).orEmpty()
            !keyboard.startsWith(NINE_KEYS) || code !in '2'..'9' -> ""
            else -> keyboard.removePrefix(NINE_KEYS).split(',').getOrElse(code - '2') { "" }
        }

        private val NINE_KEYS = Kinds.nineKeys("")
        // the 14 keys' alone: a grouped 双拼 key is filed by its code, its initial's key being the scheme's (E2)
        private val GROUPED = Kinds.keys("")
    }
}
