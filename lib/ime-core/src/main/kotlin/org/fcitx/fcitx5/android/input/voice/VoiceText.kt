/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

object VoiceText {

    // a cough or a breath taken for speech comes back as one of these
    private const val FILLERS = "嗯呃额啊唔哦噢欸诶呀"
    private const val PAUSES = VoiceMarks.PAUSES
    private val FILLER = Regex("^[$FILLERS$PAUSES]+$")

    // a cough comes back as 咳咳咳… or 哼哼哼, a breath as "Huh,"; two or more 咳/哼 so a dictated 哼。 still types
    private val COUGH = Regex("^(?=(?:.*[咳哼]){2})[咳哼$FILLERS$PAUSES]+$")

    // checked a word at a time: one `(sound pause*)+` regex backtracks 2^n ways on "mmmm…m okay"
    private val LATIN_SOUND = Regex("h+u+h+|h+m+|u+h+|u+m+|m+h*m+|a+h+", RegexOption.IGNORE_CASE)
    private val PAUSE = Regex("[$PAUSES]+")

    // longer is speech, keeping the per-word check small
    private const val LONGEST_SOUND = 64

    private fun isLatinSound(text: String): Boolean {
        if (PAUSE.matches(text.substring(0, 1))) return false
        val words = text.split(PAUSE).filter { it.isNotEmpty() }
        return words.isNotEmpty() && words.all { isSounds(it) }
    }

    private fun isSounds(word: String): Boolean {
        if (word.length > LONGEST_SOUND) return false
        val ends = BooleanArray(word.length + 1).also { it[0] = true }
        for (to in 1..word.length) {
            ends[to] = (0 until to).any { ends[it] && LATIN_SOUND.matches(word.substring(it, to)) }
        }
        return ends[word.length]
    }

    private val NOISE = listOf(FILLER, COUGH)

    // X-ASR spaces Chinese as it does English: "对啊， 大家觉得 AI 有一天"
    private const val CJK = "\\p{IsHan}，。！？、：；“”‘’（）《》…—"
    private val SPACE_BY_CJK = Regex("(?<=[$CJK])\\s+|\\s+(?=[$CJK])")

    // and spells abbreviations out ("C E P"). Not \b: ICU and Java ≤18 count 了 as a word char, so 订了C had no boundary
    private val SPELLED = Regex("(?<![$LATIN_WORD])[A-Z](?: [A-Z](?![$LATIN_WORD]))+")

    // the Latin letters, digits and _ \b counts as a word's: "éA B" and "x_A B" are not spelled
    private const val LATIN_WORD = "\\p{IsLatin}0-9_"

    /** No spaces in Chinese, an abbreviation in one piece; null for punctuation or a filler sound alone ("嗯。", "Huh,"). */
    fun clean(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || NOISE.any { it.matches(text) } || isLatinSound(text)) return null
        return SPELLED.replace(SPACE_BY_CJK.replace(text, "")) { it.value.replace(" ", "") }
    }
}
