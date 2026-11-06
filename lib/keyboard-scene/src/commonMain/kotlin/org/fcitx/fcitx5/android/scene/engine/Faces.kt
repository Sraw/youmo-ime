/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.layout.Face
import org.fcitx.fcitx5.android.scene.theme.KeyLook
import org.fcitx.fcitx5.android.scene.theme.Radius
import kotlin.math.min

/**
 * Where a key's layers go in its cell: [face] (null: none drawn), the [shadow] under it, its border's
 * width, the [pressed] layer, and [content], what its text and marks are placed in (the cell less
 * its inset, whatever the face's shape).
 */
class FaceGeometry(val face: Shape.Rect?, val shadow: Shape.Rect?, val border: Double, val pressed: Shape.Rect, val content: Box)

/**
 * Rules B §2 (ime-core's KeyFaceGeometry, copied here as the theme's values): the face is the cell
 * less its `inset` (theme 3.0's gap), rounded by `radius` but no more than half its shorter side;
 * the shadow is the face drawn down by its offset, kept within the cell (never on the row below).
 * A `bar` (the flat space) is the cell less 10 dp a side and as much top and bottom as leaves it 26 dp,
 * 16 dp at most; a `circle` (the flat Return) is as wide as the cell's shorter side, 35 dp at most,
 * in its middle. A key whose layout says `face="none"` draws no face (its pressed layer still shows);
 * `face="default"` on flat keys is a rectangle whatever the theme's shape (theme 3.0: only the space
 * and Return, `face="special"`, were a bar or a circle).
 */
object Faces {
    const val SPACE_MIN_HEIGHT_DP = 26.0
    const val SPACE_H_INSET_DP = 10.0
    const val SPACE_MAX_V_INSET_DP = 16.0
    const val ENTER_MAX_DP = 35.0

    fun of(cell: Box, look: KeyLook, face: Face, margin: Boolean, flat: Boolean, density: Double): FaceGeometry {
        val inset = look.inset
        val k = if (margin) density else 0.0
        val inner = cell.inset(inset.left * k, inset.top * k, inset.right * k, inset.bottom * k)
        val shape = if (face == Face.Default && flat) "rect" else look.shape
        val faceShape = when (shape) {
            "bar" -> {
                val h = SPACE_H_INSET_DP * density
                val v = if (cell.height < SPACE_MIN_HEIGHT_DP * density) 0.0 else min((cell.height - SPACE_MIN_HEIGHT_DP * density) / 2, SPACE_MAX_V_INSET_DP * density)
                val bar = cell.inset(h, v, h, v)
                Shape.Rect(bar, radii(look.radius, bar, density))
            }
            "circle" -> {
                val size = min(min(cell.width, cell.height), ENTER_MAX_DP * density)
                val left = cell.left + (cell.width - size) / 2
                val top = cell.top + (cell.height - size) / 2
                Shape.Rect(Box(left, top, left + size, top + size), List(CORNERS) { size / 2 })
            }
            else -> Shape.Rect(inner, radii(look.radius, inner, density))
        }
        val drawn = faceShape.takeIf { face != Face.None && it.box.hasSize }
        val s = look.shadow
        val shadow = drawn?.takeIf { s.color != null && (s.dx != 0.0 || s.dy != 0.0 || s.blur != 0.0) }?.let {
            val box = it.box.union(it.box.moved(s.dx * density, s.dy * density)).intersect(cell)
            Shape.Rect(box, it.radii)
        }
        val border = if (drawn == null) 0.0 else look.border.width * density
        return FaceGeometry(drawn, shadow, border, faceShape, inner)
    }

    /** [r] in px for [box]: each corner no more than half its shorter side, none where it has no size. */
    fun radii(r: Radius, box: Box, density: Double): List<Double> {
        val max = maxOf(0.0, min(box.width, box.height) / 2)
        return if (r.half) List(CORNERS) { max } else r.corners.map { (it * density).coerceIn(0.0, max) }
    }

    private const val CORNERS = 4
}
