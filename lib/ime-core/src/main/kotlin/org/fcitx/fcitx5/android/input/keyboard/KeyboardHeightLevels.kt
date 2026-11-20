/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * 键盘高度's levels. Upright: the keys' share of the screen's height. Turned: the value names a row's dp in
 * [landscapeRowDp]'s table, so keys are as tall on any sideways screen; stored values stayed (`49` is 标准) so 0.6.0
 * reads them as its percentages. 紧凑 draws smaller text ([KeyTextScale]) and no corner marks under the letter.
 */
object KeyboardHeightLevels {

    data class Level(val portrait: Int, val landscapeLevel: Int)

    /** 紧凑, 矮, 标准, 高, 很高, in the settings list's order. */
    val ALL = listOf(Level(20, 34), Level(26, 42), Level(30, 49), Level(34, 55), Level(38, 60))

    const val COMPACT = 0

    /** A display short side this wide or more (a foldable's inner screen, a tablet) takes [LARGE_ROW_DP]'s rows turned. */
    const val LARGE_SHORT_SIDE_DP = 600

    // 横屏's row at each level, dp: 标准 48 on a phone (as 搜狗, 讯飞), 56 on a large screen (as Gboard)
    private val PHONE_ROW_DP = doubleArrayOf(34.0, 41.0, 48.0, 54.0, 59.0)
    private val LARGE_ROW_DP = doubleArrayOf(48.0, 52.0, 56.0, 60.0, 64.0)

    // past the table's ends (a theme's height, an old value): a row this much a level lower, higher, down to the floor
    private const val BELOW_DP_PER_LEVEL = 0.5
    private const val ABOVE_DP_PER_LEVEL = 0.8
    private const val LOWEST_ROW_DP = 28.0

    /** Interpolated between levels, extended past the ends by the per-level dp ([LOWEST_ROW_DP] at least). */
    fun landscapeRowDp(level: Double, large: Boolean): Double {
        val rows = if (large) LARGE_ROW_DP else PHONE_ROW_DP
        val at = ALL.map { it.landscapeLevel.toDouble() }
        if (level <= at.first()) return maxOf(LOWEST_ROW_DP, rows.first() - (at.first() - level) * BELOW_DP_PER_LEVEL)
        if (level >= at.last()) return rows.last() + (level - at.last()) * ABOVE_DP_PER_LEVEL
        val i = at.indexOfLast { it <= level }
        return rows[i] + (rows[i + 1] - rows[i]) * (level - at[i]) / (at[i + 1] - at[i])
    }

    /** As the settings list shows it ([LevelPick]). */
    fun nearest(portrait: Int, landscape: Int): Int =
        LevelPick.nearest(listOf(portrait, landscape), ALL.map { listOf(it.portrait, it.landscapeLevel) })

    /** One answer for both orientations, as the setting is one. */
    fun isCompact(portrait: Int, landscape: Int): Boolean = nearest(portrait, landscape) == COMPACT
}
