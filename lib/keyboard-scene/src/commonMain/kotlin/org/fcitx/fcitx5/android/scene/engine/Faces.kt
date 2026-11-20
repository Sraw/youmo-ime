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
import org.fcitx.fcitx5.android.scene.theme.ShapePath
import kotlin.math.min

/** [content] is what text and marks are placed in: the cell less its inset, whatever the face's shape, less its [depth]. */
class FaceGeometry(
    val face: Shape?,
    val shadow: Shape?,
    val border: Double,
    val pressed: Shape,
    val content: Box,
    val line: Shape? = null,
    val depth: Shape? = null,
)

/**
 * Rules B §2 (ime-core's KeyFaceGeometry): the face is the cell less its inset, rounded no more than half its shorter
 * side; the shadow is the face moved, kept within the cell (never on the row below). A `depth` takes that much off the
 * face's bottom and draws the face moved down by it, under it, as the cap's side.
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
        val plain = face == Face.Default && flat
        val shape = if (plain) "rect" else look.shape
        val path = look.path?.takeIf { !plain }
        // the flat space's bar and Return's circle are no caps
        val capped = face != Face.None && (path != null || shape !in listOf("bar", "circle"))
        val depth = if (capped) min(look.depth * density, maxOf(0.0, inner.height / 2)) else 0.0
        val top = inner.inset(0.0, 0.0, 0.0, depth)
        // one turned inside out (an inset past the cell) would draw it mirrored, out of the cell
        val faceShape: Shape = path?.takeIf { top.hasSize }?.flatten(top) ?: rect(shape, cell, top, look, density)
        val drawn = faceShape.takeIf { face != Face.None && it.bounds.hasSize }
        val cap = drawn?.takeIf { depth > 0 }?.moved(0.0, depth)
        val shadow = drawn?.let { shadow(if (cap == null) it else outline(it, cap), look, cell, density) }
        val border = if (drawn == null) 0.0 else look.border.width * density
        val line = drawn?.takeIf { border > 0 }?.let { line(it, border, path, top) }
        return FaceGeometry(drawn, shadow, border, faceShape, top, line, cap)
    }

    private fun rect(shape: String, cell: Box, top: Box, look: KeyLook, density: Double): Shape.Rect = when (shape) {
        "bar" -> {
            val h = SPACE_H_INSET_DP * density
            val v = if (cell.height < SPACE_MIN_HEIGHT_DP * density) 0.0 else min((cell.height - SPACE_MIN_HEIGHT_DP * density) / 2, SPACE_MAX_V_INSET_DP * density)
            val bar = cell.inset(h, v, h, v)
            Shape.Rect(bar, radii(look.radius, bar, density))
        }
        "circle" -> {
            val size = min(min(cell.width, cell.height), ENTER_MAX_DP * density)
            val left = cell.left + (cell.width - size) / 2
            val y = cell.top + (cell.height - size) / 2
            Shape.Rect(Box(left, y, left + size, y + size), List(CORNERS) { size / 2 })
        }
        else -> Shape.Rect(top, radii(look.radius, top, density))
    }

    private fun shadow(whole: Shape, look: KeyLook, cell: Box, density: Double): Shape? {
        val s = look.shadow.takeIf { it.dx != 0.0 || it.dy != 0.0 || it.blur != 0.0 } ?: return null
        if (s.color == null) return null
        return when (whole) {
            is Shape.Rect -> Shape.Rect(whole.box.union(whole.box.moved(s.dx * density, s.dy * density)).intersect(cell), whole.radii)
            is Shape.Path -> within(whole.moved(s.dx * density, s.dy * density), cell)
        }
    }

    /** Half its width in, so all of it is inside; a path's drawn again in [box] less half the width a side. */
    fun line(face: Shape, width: Double, path: ShapePath?, box: Box): Shape {
        val w = width / 2
        return when {
            face is Shape.Rect -> Shape.Rect(face.box.inset(w, w, w, w), face.radii.map { maxOf(0.0, it - w) })
            path != null -> path.flatten(box.inset(w, w, w, w))
            else -> face
        }
    }

    // a path its cap's (one outline, not two overlapping, which even-odd would leave a hole in), the face over the rest
    private fun outline(face: Shape, cap: Shape): Shape = when (face) {
        is Shape.Rect -> Shape.Rect(face.box.union(cap.bounds), face.radii)
        is Shape.Path -> cap
    }

    private fun within(p: Shape.Path, box: Box) = Shape.Path(
        p.ops.map {
            when (it) {
                is Shape.Path.Op.MoveTo -> Shape.Path.Op.MoveTo(it.x.coerceIn(box.left, box.right), it.y.coerceIn(box.top, box.bottom))
                is Shape.Path.Op.LineTo -> Shape.Path.Op.LineTo(it.x.coerceIn(box.left, box.right), it.y.coerceIn(box.top, box.bottom))
                Shape.Path.Op.Close -> it
            }
        },
    )

    fun radii(r: Radius, box: Box, density: Double): List<Double> {
        val max = maxOf(0.0, min(box.width, box.height) / 2)
        return if (r.half) List(CORNERS) { max } else r.corners.map { (it * density).coerceIn(0.0, max) }
    }

    private const val CORNERS = 4
}
