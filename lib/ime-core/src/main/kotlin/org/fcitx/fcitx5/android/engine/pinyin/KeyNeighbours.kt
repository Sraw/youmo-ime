/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

/** Letters a finger may hit instead of the one meant on the app's QWERTY: either side and the touching keys above and below (rows offset half a key and a key and a half). */
object KeyNeighbours {

    private val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    // each row's first key start, in key widths
    private val OFFSETS = doubleArrayOf(0.0, 0.5, 1.5)

    private val neighbours: Map<Char, String> = HashMap<Char, String>().apply {
        for ((r, row) in ROWS.withIndex()) {
            for ((i, c) in row.withIndex()) {
                val x = OFFSETS[r] + i
                put(c, buildString {
                    for (other in maxOf(0, r - 1)..minOf(ROWS.size - 1, r + 1)) {
                        for ((j, d) in ROWS[other].withIndex()) {
                            val dx = Math.abs(OFFSETS[other] + j - x)
                            if (d != c && (if (other == r) dx <= 1.0 else dx < 1.0)) append(d)
                        }
                    }
                })
            }
        }
    }

    fun of(c: Char): String = neighbours[c].orEmpty()
}
