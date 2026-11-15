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

/**
 * The keys give way so the app keeps at least [MIN_APP_DP] over the keyboard (061-landscape-height §5.2; it took the
 * place of 0.6.0's 70% of the screen, which left an app 72–88 dp turned under gesture navigation, the bars not
 * counted), but never below [MIN_ROW_DP] a row: there the rows win and the app gets less (the user, 2026-11-14, over
 * 2026-10-22's 封顶赢). The level's own keys lower than that (紧凑) are not raised. Upright the levels are far from it.
 */
object KeyboardHeightCap {
    const val MIN_APP_DP = 120
    const val MIN_ROW_DP = 36

    /**
     * The keys' height: [requested] unless the app would be left less than [MIN_APP_DP] of [available] (ImeRoom)
     * over the keyboard, then down to [rows] × [MIN_ROW_DP]; never below 0. [barsHeight] is everything docked above
     * the keys: the candidate bar and the code row in the keyboard (the preedit line; a code row floating over the
     * app comes and goes and is not counted, as contentTopInsets never counted it), each fixed while one input method
     * is shown, so no row pushes the keys about. [handleHeight] a docked drag handle (始终显示悬浮条), added to the
     * keyboard. [rows] the layout's rows as [rowUnits] counts them; [density] px a dp.
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

    /**
     * The rows a layout has in rows of its tallest ([heights], each row's weight): a row half as tall is half a row.
     * By the tallest, not the shortest: a custom layout's thin row (a weight down to 0, LayoutReader) would count the
     * others many times over and put the floor over the room (four rows and a half one by the shortest: nine, 324 dp).
     * A tall row over short ones (the symbols' 1 and 3) counts less than their number: the floor is the rows' height.
     */
    fun rowUnits(heights: List<Double>): Double {
        val tallest = heights.filter { it > 0 }.maxOrNull() ?: return DEFAULT_ROWS
        return heights.filter { it > 0 }.sum() / tallest
    }

    /**
     * [rowUnits] of [layout] as [env] expands it: the keyboard's (KeyboardWindow), the settings' caps and their notes
     * (KeyCaps, SwipeFates) count a board's rows one way, 注音's five; four where it does not expand.
     */
    fun rowUnits(layout: Layout, fragments: Map<String, Fragment>, env: Env): Double =
        rowUnits(Expand.rows(layout, fragments, env).getOr { return DEFAULT_ROWS }.map { it.height })

    /** A layout of letters: four rows (a layout not read yet counts so). */
    const val DEFAULT_ROWS = 4.0
}
