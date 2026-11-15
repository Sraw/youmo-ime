/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * 键盘高度's levels, upright and turned. Upright, the keys' share of the screen's height, in percent. Turned (横屏), a
 * level's value is no share of anything since 0.6.1 (061-landscape-height §5.1): it names a row's height, dp, in
 * [landscapeRowDp]'s table, a phone's or a large screen's, so a key is as tall to the finger on any screen held sideways.
 * The stored values did not change (`49` is still 标准), so 0.6.0 reading them back takes them as its percentages.
 * 紧凑 is the lowest (0.5.1): its keys' text is smaller ([KeyTextScale]) and their corners draw
 * nothing under the letter ([KeyMarks]).
 */
object KeyboardHeightLevels {

    /** A level: [portrait] percent of the screen's height, [landscapeLevel] 横屏's value in [landscapeRowDp]'s table. */
    data class Level(val portrait: Int, val landscapeLevel: Int)

    /** 紧凑, 矮, 标准, 高, 很高: what the settings list shows, in its order. */
    val ALL = listOf(Level(20, 34), Level(26, 42), Level(30, 49), Level(34, 55), Level(38, 60))

    const val COMPACT = 0

    /**
     * A screen whose display's short side is this wide or more (a foldable's inner screen, a tablet) has
     * [LARGE_ROW_DP]'s rows turned; a phone held sideways, however wide, [PHONE_ROW_DP]'s.
     */
    const val LARGE_SHORT_SIDE_DP = 600

    // 横屏's row at each level, dp: a phone's are today's on a common phone (393 dp high; 标准 48, as 搜狗 48.7 and
    // 讯飞 47.5), a large screen's 标准 Gboard's 56 (061-landscape-height §2.2, §5.1)
    private val PHONE_ROW_DP = doubleArrayOf(34.0, 41.0, 48.0, 54.0, 59.0)
    private val LARGE_ROW_DP = doubleArrayOf(48.0, 52.0, 56.0, 60.0, 64.0)

    // past the table's ends (a theme's height, a value 0.4 dragged out): a row this much a level lower, higher, down to the floor
    private const val BELOW_DP_PER_LEVEL = 0.5
    private const val ABOVE_DP_PER_LEVEL = 0.8
    private const val LOWEST_ROW_DP = 28.0

    /**
     * 横屏's row, dp, at [level] ([Level.landscapeLevel], or a theme's landscape `height`): the table's at a level,
     * straight between two, [BELOW_DP_PER_LEVEL] a level below the first (no lower than [LOWEST_ROW_DP]) and
     * [ABOVE_DP_PER_LEVEL] a level above the last; [large] a screen [LARGE_SHORT_SIDE_DP] across or more.
     */
    fun landscapeRowDp(level: Double, large: Boolean): Double {
        val rows = if (large) LARGE_ROW_DP else PHONE_ROW_DP
        val at = ALL.map { it.landscapeLevel.toDouble() }
        if (level <= at.first()) return maxOf(LOWEST_ROW_DP, rows.first() - (at.first() - level) * BELOW_DP_PER_LEVEL)
        if (level >= at.last()) return rows.last() + (level - at.last()) * ABOVE_DP_PER_LEVEL
        val i = at.indexOfLast { it <= level }
        return rows[i] + (rows[i + 1] - rows[i]) * (level - at[i]) / (at[i + 1] - at[i])
    }

    /** The level [portrait] and [landscape] are at, as the settings list shows it ([LevelPick]). */
    fun nearest(portrait: Int, landscape: Int): Int =
        LevelPick.nearest(listOf(portrait, landscape), ALL.map { listOf(it.portrait, it.landscapeLevel) })

    /** Whether the keyboard is 紧凑: one answer for both orientations, as the setting is one. */
    fun isCompact(portrait: Int, landscape: Int): Boolean = nearest(portrait, landscape) == COMPACT
}
