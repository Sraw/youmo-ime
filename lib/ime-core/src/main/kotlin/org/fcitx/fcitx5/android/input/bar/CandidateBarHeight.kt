/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.engine.reading.ReadingStyle

/**
 * The bar's height from 候选字号: [DEFAULT_DP] at [DEFAULT_TEXT_SP], two dp per sp; the keys keep their height. 候选词注音
 * in pinyin adds a line of [READING_LINE]; zhuyin stands to the right and adds width, not height.
 */
object CandidateBarHeight {
    const val DEFAULT_TEXT_SP = 20
    const val DEFAULT_DP = 40
    private const val FULL = 100.0

    /** Of the candidates'. */
    const val READING_SCALE = 0.5f

    /** Of their text size: half their size, and its line's spacing. */
    const val READING_LINE = 0.6f

    /** Of the text size (Noto CJK's ideographs: 0.88 em). */
    const val HAN_TOP = 0.88f

    /** Smaller, pinyin's tone marks are hard to tell apart. */
    const val HINT_FLOOR_SP = 7f

    /** A hint's reading (候选词注音 off) as large as fits above the characters, [READING_SCALE] at most, [floor] at least; the bar keeps its height. */
    fun hintSize(textSize: Float, room: Float, line: Float, floor: Float): Float {
        val most = textSize * READING_SCALE
        return (room / line).coerceIn(minOf(floor, most), most)
    }

    /** A handwritten character's readings: [READING_SCALE] where they fit, else smaller down to [floor] (the candidate widened); 0: never smaller. */
    fun readingsSize(textSize: Float, charWidth: Float, width: Float, floor: Float): Float {
        val most = textSize * READING_SCALE
        if (width <= charWidth || floor <= 0f) return most
        return (most * charWidth / width).coerceIn(minOf(floor, most), most)
    }

    /** [percent]: the theme's `bar height=`, 80–140; a readings line is added after. */
    fun dp(textSp: Int, reading: ReadingStyle = ReadingStyle.OFF, percent: Double = FULL): Int {
        val bar = Math.round((DEFAULT_DP + 2 * (textSp - DEFAULT_TEXT_SP)) * percent / FULL).toInt()
        return if (reading == ReadingStyle.PINYIN) bar + Math.round(textSp * READING_LINE) else bar
    }
}
