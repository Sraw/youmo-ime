/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/** What a recognized stretch of speech types. */
object VoiceText {

    // a cough or a breath taken for speech comes back as one of these
    private const val FILLERS = "嗯呃额啊唔哦噢欸诶呀"
    private const val PAUSES = "。，、！？.,!?…\\s"
    private val FILLER = Regex("^[$FILLERS$PAUSES]+$")

    // a cough or throat clearing comes back as 咳咳咳… or 哼哼哼 (voice-eval's noise set: 9 of 159
    // clips), a breath as "Huh,". Two or more of 咳/哼, so a single dictated 哼。 still types;
    // with any other word ("哼哈哈", "嗯哼因为…") it is speech and types
    private val COUGH = Regex("^(?=(?:.*[咳哼]){2})[咳哼$FILLERS$PAUSES]+$")

    // "Hmm.", "Uh huh,": sounds, each made of these, with or without pauses between. Checked a
    // word at a time, not as one `(sound pause*)+`: that splits "mmmm…m okay" 2^n ways before it
    // fails, and it runs on each result the recognizer hands back
    private val LATIN_SOUND = Regex("h+u+h+|h+m+|u+h+|u+m+|m+h*m+|a+h+", RegexOption.IGNORE_CASE)
    private val PAUSE = Regex("[$PAUSES]+")

    // longer is taken as speech, and keeps the per-word check below small: a hum of more letters
    // ("mmm…" × 65) is typed, where the one regex dropped it; no recognizer hands one back
    private const val LONGEST_SOUND = 64

    private fun isLatinSound(text: String): Boolean {
        if (PAUSE.matches(text.substring(0, 1))) return false
        val words = text.split(PAUSE).filter { it.isNotEmpty() }
        return words.isNotEmpty() && words.all { isSounds(it) }
    }

    // whether [word] is sounds back to back: which of its prefixes are
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

    // and spells an abbreviation out: "C E P". Not \b: Android's regex (ICU) and Java up to 18
    // count 了 as a word char, so 订了C had no boundary there and C E P stayed spelled; Java 19+
    // looks at ASCII only, which hid it on the desktop
    private val SPELLED = Regex("(?<![$LATIN_WORD])[A-Z](?: [A-Z](?![$LATIN_WORD]))+")

    // the Latin letters, digits and _ \b counts as a word's: "éA B" and "x_A B" are not spelled
    private const val LATIN_WORD = "\\p{IsLatin}0-9_"

    /**
     * [raw] as typed: no spaces in Chinese, an abbreviation in one piece, numbers in digits
     * ([ChineseNumbers]); null when nothing is left worth typing: punctuation alone, or a filler
     * sound alone ("嗯。", "咳咳咳。", "Huh,"), which the recognizer writes for a noise as often as for a word.
     */
    fun clean(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || NOISE.any { it.matches(text) } || isLatinSound(text)) return null
        val spaced = SPELLED.replace(SPACE_BY_CJK.replace(text, "")) { it.value.replace(" ", "") }
        return ChineseNumbers.convert(spaced)
    }
}
