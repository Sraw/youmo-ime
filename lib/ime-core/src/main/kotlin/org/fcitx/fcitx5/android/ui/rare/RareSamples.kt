/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.rare

import org.fcitx.fcitx5.android.engine.data.CharLevels.Level

/** A group card's sample characters: few know what 三级字 means, a look tells. Hand-picked; RareSamplesGlyphTest checks them. */
object RareSamples {

    const val COUNT = 6

    /** In the order shown. */
    val PICKED: Map<Level, List<String>> = mapOf(
        Level.EXT to listOf("𠮷", "𡃁", "𨋢", "𠹷", "𦧲", "𩵋"),
        Level.L3 to listOf("犇", "堃", "喆", "淼", "垚", "芃"),
        Level.TRAD to listOf("愛", "國", "龍", "體", "學", "邊"),
        Level.OUT to listOf("龘", "囧", "槑", "嫑", "夲", "烎"),
    )

    /** The picked ones the phone [draws]; too few, the rest spread evenly over [shown] so not all one sound. */
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
