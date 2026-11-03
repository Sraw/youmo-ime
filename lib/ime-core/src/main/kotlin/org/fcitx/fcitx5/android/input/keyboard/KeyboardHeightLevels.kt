/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * 键盘高度's levels: the keys' share of the screen's height, in percent, upright and turned.
 * 紧凑 is the lowest (0.5.1): its keys' text is smaller ([KeyTextScale]) and their corners draw
 * nothing under the letter ([KeyMarks]).
 */
object KeyboardHeightLevels {

    data class Level(val portrait: Int, val landscape: Int)

    /** 紧凑, 矮, 标准, 高, 很高: what the settings list shows, in its order. */
    val ALL = listOf(Level(20, 34), Level(26, 42), Level(30, 49), Level(34, 55), Level(38, 60))

    const val COMPACT = 0

    /** The level [portrait] and [landscape] are at, as the settings list shows it ([LevelPick]). */
    fun nearest(portrait: Int, landscape: Int): Int =
        LevelPick.nearest(listOf(portrait, landscape), ALL.map { listOf(it.portrait, it.landscape) })

    /** Whether the keyboard is 紧凑: one answer for both orientations, as the setting is one. */
    fun isCompact(portrait: Int, landscape: Int): Boolean = nearest(portrait, landscape) == COMPACT
}
