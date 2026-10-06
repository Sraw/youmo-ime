/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/** What a recognized stretch of speech types. */
object VoiceText {

    // a cough or a breath taken for speech comes back as one of these
    private val FILLER = Regex("^[嗯呃额啊唔哦噢欸诶呀。，、！？.,!?…\\s]+$")

    // X-ASR spaces Chinese as it does English: "对啊， 大家觉得 AI 有一天"
    private const val CJK = "\\p{IsHan}，。！？、：；“”‘’（）《》…—"
    private val SPACE_BY_CJK = Regex("(?<=[$CJK])\\s+|\\s+(?=[$CJK])")

    // and spells an abbreviation out: "C E P"
    private val SPELLED = Regex("\\b[A-Z](?: [A-Z]\\b)+")

    /**
     * [raw] as typed: no spaces in Chinese, an abbreviation in one piece, numbers in digits
     * ([ChineseNumbers]); null when nothing is left worth typing: punctuation alone, or a filler
     * sound alone ("嗯。"), which the recognizer writes for a noise as often as for a word.
     */
    fun clean(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || FILLER.matches(text)) return null
        val spaced = SPELLED.replace(SPACE_BY_CJK.replace(text, "")) { it.value.replace(" ", "") }
        return ChineseNumbers.convert(spaced)
    }
}
