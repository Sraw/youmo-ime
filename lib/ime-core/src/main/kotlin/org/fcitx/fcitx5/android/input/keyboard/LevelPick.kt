/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import kotlin.math.abs

/** Levels are stored nowhere: the nearest to what the preferences hold, the same answer for settings and keyboard. */
object LevelPick {

    /** Distances scaled by how far apart the levels set each preference; a tie goes to the earlier (an old 23% / 38% is 紧凑). */
    fun nearest(held: List<Int>, levels: List<List<Int>>): Int {
        require(levels.isNotEmpty() && levels.all { it.size == held.size }) { "levels of ${held.size} values" }
        val spans = held.indices.map { k -> levels.maxOf { it[k] } - levels.minOf { it[k] } }
        return levels.indices.minBy { i ->
            held.indices.sumOf { k -> abs(held[k] - levels[i][k]).toDouble() / spans[k].coerceAtLeast(1) }
        }
    }
}
