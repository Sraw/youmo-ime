/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.rare

import org.fcitx.fcitx5.android.engine.data.CharLevels.Level

/**
 * The few characters a group card shows (dev/design/061-settings-cards.md §3.1): few know what 三级字
 * is, a look at its characters tells. Picked by hand, ones people know and that say what kind the
 * group is; each is its group's (RareSamplesGlyphTest checks it against the APK's table, and that the
 * phone draws it).
 */
object RareSamples {

    const val COUNT = 6

    /** Each group's picked characters, in the order shown. */
    val PICKED: Map<Level, List<String>> = mapOf(
        Level.EXT to listOf("𠮷", "𡃁", "𨋢", "𠹷", "𦧲", "𩵋"),
        Level.L3 to listOf("犇", "堃", "喆", "淼", "垚", "芃"),
        Level.TRAD to listOf("愛", "國", "龍", "體", "學", "邊"),
        Level.OUT to listOf("龘", "囧", "槑", "嫑", "夲", "烎"),
    )

    /**
     * Up to [n] characters for [level]'s card: the picked ones the phone [draws], in order; too few,
     * the rest taken evenly spread from [shown], the group's characters the phone draws in the grid's
     * order (by reading), so they are not all of one sound. None drawn, none.
     */
    fun of(level: Level, shown: List<String>, draws: (String) -> Boolean, n: Int = COUNT): List<String> {
        require(n >= 0) { "n $n" }
        val picked = PICKED[level].orEmpty().filter(draws).take(n)
        val need = n - picked.size
        if (need == 0) return picked
        val pool = shown.filter { it !in picked }
        val take = minOf(need, pool.size)
        return picked + List(take) { pool[it * pool.size / take] }
    }
}
