/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import kotlin.math.abs

/**
 * Which of a few named levels some int preferences are at (键盘高度, 振动强度 ...): the level is stored
 * nowhere, it is the nearest to what they hold. The settings list shows it, and the keyboard asks the
 * same of the height (紧凑 or not, [KeyboardHeightLevels]), so the two never disagree.
 */
object LevelPick {

    /**
     * The index of the level of [levels] (each a value per preference, in [held]'s order) nearest
     * [held]: each preference's distance over how far apart the levels set it, so one in ms and one of
     * 0..255 weigh alike. Exactly halfway between two, the earlier in [levels] (an old 23% / 38%
     * height is 紧凑).
     */
    fun nearest(held: List<Int>, levels: List<List<Int>>): Int {
        require(levels.isNotEmpty() && levels.all { it.size == held.size }) { "levels of ${held.size} values" }
        val spans = held.indices.map { k -> levels.maxOf { it[k] } - levels.minOf { it[k] } }
        return levels.indices.minBy { i ->
            held.indices.sumOf { k -> abs(held[k] - levels[i][k]).toDouble() / spans[k].coerceAtLeast(1) }
        }
    }
}
