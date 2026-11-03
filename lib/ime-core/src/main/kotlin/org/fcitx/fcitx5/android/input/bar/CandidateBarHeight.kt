/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.engine.reading.ReadingStyle

/**
 * The candidate bar's height from 候选字号: at the default [DEFAULT_TEXT_SP] it is the bar's old
 * fixed [DEFAULT_DP]; each sp more or less moves it by two dp, so the words keep the same margin
 * above and below. The keys keep their height: the keyboard grows with the bar (the user, 2026-10-22).
 *
 * 候选词注音 in pinyin puts a line above the words, its text half theirs ([READING_SCALE]): the bar grows
 * by that line, [READING_LINE] of the text size. Zhuyin stands upright to the right of each character,
 * so it widens the candidates and adds no height.
 */
object CandidateBarHeight {
    const val DEFAULT_TEXT_SP = 20
    const val DEFAULT_DP = 40

    /** The readings' text size, of the candidates'. */
    const val READING_SCALE = 0.5f

    /** A line of pinyin above the words, of their text size: half their size, and its line's spacing. */
    const val READING_LINE = 0.6f

    /** Where a Han character's top stands above its baseline, of the text size (Noto CJK's ideographs: 0.88 em). */
    const val HAN_TOP = 0.88f

    /** The smallest a hint's reading is drawn, in sp: smaller, pinyin's tone marks are hard to tell apart. */
    const val HINT_FLOOR_SP = 7f

    /**
     * The text size of a hint's reading above a candidate (候选词注音 off: a misreading's, a part's, a stroke
     * lookup's): as large as fits the [room] between the view's top and the characters' tops, its line [line]
     * of its size, at most a reading's [READING_SCALE] of [textSize] and at least [floor]. The bar keeps its
     * height (the user, 2026-11-02); at the floor the reading may reach a little into the characters' tops.
     */
    fun hintSize(textSize: Float, room: Float, line: Float, floor: Float): Float {
        val most = textSize * READING_SCALE
        return (room / line).coerceIn(minOf(floor, most), most)
    }

    fun dp(textSp: Int, reading: ReadingStyle = ReadingStyle.OFF): Int {
        val bar = DEFAULT_DP + 2 * (textSp - DEFAULT_TEXT_SP)
        return if (reading == ReadingStyle.PINYIN) bar + Math.round(textSp * READING_LINE) else bar
    }
}
