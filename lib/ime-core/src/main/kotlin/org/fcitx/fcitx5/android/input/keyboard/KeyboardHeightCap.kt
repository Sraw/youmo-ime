/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Expand
import org.fcitx.fcitx5.android.scene.getOr
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Layout
import kotlin.math.roundToInt

/** The keys give way so the app keeps [MIN_APP_DP] over the keyboard, but never below [MIN_ROW_DP] a row; a level's own lower keys are not raised. */
object KeyboardHeightCap {
    const val MIN_APP_DP = 120
    const val MIN_ROW_DP = 36

    /**
     * [barsHeight]: everything docked above the keys, fixed per input method (a floating code row not counted, as
     * contentTopInsets never did); [handleHeight]: a docked drag handle; [rows] as [rowUnits] counts them.
     */
    fun keysHeight(
        requested: Int,
        available: Int,
        barsHeight: Int,
        bottomPadding: Int,
        handleHeight: Int = 0,
        rows: Double = DEFAULT_ROWS,
        density: Float = 1f,
    ): Int {
        val room = available - barsHeight - handleHeight - bottomPadding - (MIN_APP_DP * density).roundToInt()
        val least = (rows * MIN_ROW_DP * density).roundToInt()
        return maxOf(minOf(requested, room), minOf(requested, least)).coerceAtLeast(0)
    }

    /** Rows in units of the tallest: by the shortest, a thin custom row would count the others many times and push the floor past the room. */
    fun rowUnits(heights: List<Double>): Double {
        val tallest = heights.filter { it > 0 }.maxOrNull() ?: return DEFAULT_ROWS
        return heights.filter { it > 0 }.sum() / tallest
    }

    /** As the keyboard and settings expand it (注音's five); four where it does not expand. */
    fun rowUnits(layout: Layout, fragments: Map<String, Fragment>, env: Env): Double =
        rowUnits(Expand.rows(layout, fragments, env).getOr { return DEFAULT_ROWS }.map { it.height })

    /** A layout not read yet counts so. */
    const val DEFAULT_ROWS = 4.0
}
