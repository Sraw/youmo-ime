/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Ink
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.Value
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The handwriting pad's own (060-render-p3 T7b), read off its slot's style (`handwriting/pad`, as its
 * face's `fill`): the strokes' `ink-color` and `ink-width`, the grid's `grid` and `grid-color`. Not
 * set: the strokes the keys' text colour, [INK_DP] wide, as HandwritingPadView drew them; no grid.
 *
 * The grid is 田字格: squares as tall as the pad's face, as many as fit, in its middle, each halved
 * across and down by lines [GRID_LINE_DP] wide on whole pixels; the pad's edges are its face's own.
 */
object Pads {
    /** The handwriting pad's kind of slot (layouts/handwriting.xml). */
    const val KIND = "handwriting-pad"

    /** The strokes' width not set, dp (HandwritingPadView's of before). */
    const val INK_DP = 3.5

    /** A grid line's width, dp. */
    const val GRID_LINE_DP = 1.0

    private const val KEY_TEXT = "keyText"
    private const val DIVIDER = "divider"

    /** The pad's strokes, of its style [v] (the slot's cascade), px on a screen of [density]. */
    fun ink(v: Map<String, Value>, theme: Theme, density: Double): Ink = Ink(
        color = (v["ink-color"] as? Value.OfColor)?.color?.let(theme::color) ?: theme.colors[KEY_TEXT] ?: Argb.TRANSPARENT,
        width = ((v["ink-width"] as? Value.Number)?.number ?: INK_DP) * density,
    )

    /** The grid over the pad's [face] (its box): none unless `grid="true"`; its colour `grid-color`, else the divider's. */
    fun grid(v: Map<String, Value>, theme: Theme, face: Box, density: Double): List<Op> {
        if ((v["grid"] as? Value.Flag)?.on != true || !face.hasSize) return emptyList()
        val color = (v["grid-color"] as? Value.OfColor)?.color?.let(theme::color) ?: theme.colors[DIVIDER] ?: return emptyList()
        if (color.transparent) return emptyList()
        val w = max(1.0, (GRID_LINE_DP * density).roundToInt().toDouble())
        val side = face.height
        val n = max(1, floor(face.width / side).toInt())
        val left = face.left + (face.width - n * side) / 2
        val right = left + n * side
        // the squares' edges but where they are the face's, then each one's middle, then the line across them all
        val edges = (0..n).map { left + it * side }.filter { it - face.left >= w && face.right - it >= w }
        val middles = (0 until n).map { left + (it + 0.5) * side }
        val down = (edges + middles).sorted().map { x -> line(Box(snap(x - w / 2), face.top, snap(x - w / 2) + w, face.bottom), color) }
        val y = snap(face.centerY - w / 2)
        return down + line(Box(max(face.left, snap(left)), y, minOf(face.right, snap(right)), y + w), color)
    }

    private fun snap(v: Double) = floor(v)

    private fun line(box: Box, color: Argb): Op = Op.Fill(Shape.Rect(box), color)
}
