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

/**
 * Where a key's layers go in its cell: [face] (null: none drawn), the [shadow] under it, its border's
 * width and [line] (the border's middle, half its width inside the face), the [pressed] layer,
 * [content], what its text and marks are placed in (the cell less its inset, whatever the face's
 * shape, less its [depth] band), and [depth], its cap's band under the face (null: none).
 */
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
 * Rules B §2 (ime-core's KeyFaceGeometry, copied here as the theme's values): the face is the cell
 * less its `inset` (theme 3.0's gap), rounded by `radius` but no more than half its shorter side;
 * the shadow is the face drawn down by its offset, kept within the cell (never on the row below).
 * A `bar` (the flat space) is the cell less 10 dp a side and as much top and bottom as leaves it 26 dp,
 * 16 dp at most; a `circle` (the flat Return) is as wide as the cell's shorter side, 35 dp at most,
 * in its middle. A key whose layout says `face="none"` draws no face (its pressed layer still shows);
 * `face="default"` on flat keys is a rectangle whatever the theme's shape (theme 3.0: only the space
 * and Return, `face="special"`, were a bar or a circle).
 *
 * 060-render-p3 T4b: a `path(…)` is drawn in the cell less its inset (its 0–1 that box's), its curves
 * straight edges no more than [ShapePath.TOLERANCE] px off; its shadow the face moved, each point kept
 * in the cell. A `depth` takes that much off the face's bottom (the face, its content, its pressed
 * layer up as much) and draws the face moved down by it, under it, as the cap's side.
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
        // the cap's depth, px: no more than half the face; a rectangle's or a path's (the flat space's bar and
        // Return's circle are no caps), and a face of none has none
        val capped = face != Face.None && (path != null || shape !in listOf("bar", "circle"))
        val depth = if (capped) min(look.depth * density, maxOf(0.0, inner.height / 2)) else 0.0
        val top = inner.inset(0.0, 0.0, 0.0, depth)
        val faceShape: Shape = if (path != null) path.flatten(top) else when (shape) {
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
        val drawn = faceShape.takeIf { face != Face.None && it.bounds.hasSize }
        // the cap: the face's outline moved down by the depth (a path's, or the rectangle the face is up from)
        val cap = drawn?.takeIf { depth > 0 }?.moved(0.0, depth)
        val whole = drawn?.let { if (cap == null) it else outline(it, cap) }
        val s = look.shadow
        val shadow = whole?.takeIf { s.color != null && (s.dx != 0.0 || s.dy != 0.0 || s.blur != 0.0) }?.let {
            when (it) {
                is Shape.Rect -> Shape.Rect(it.box.union(it.box.moved(s.dx * density, s.dy * density)).intersect(cell), it.radii)
                is Shape.Path -> within(it.moved(s.dx * density, s.dy * density), cell)
            }
        }
        val border = if (drawn == null) 0.0 else look.border.width * density
        val line = drawn?.takeIf { border > 0 }?.let { line(it, border, path, top) }
        return FaceGeometry(drawn, shadow, border, faceShape, top, line, cap)
    }

    /**
     * The middle of a border [width] px wide along [face]: half its width in, so all of it is inside. A
     * rectangle's box less half the width a side, its corners as much smaller; a path's ([path], drawn in
     * [box]) drawn again in [box] less half the width a side (the same outline a little smaller: its edges
     * half the width in where they run along the box, about as much elsewhere).
     */
    fun line(face: Shape, width: Double, path: ShapePath?, box: Box): Shape {
        val w = width / 2
        return when {
            face is Shape.Rect -> Shape.Rect(face.box.inset(w, w, w, w), face.radii.map { maxOf(0.0, it - w) })
            path != null -> path.flatten(box.inset(w, w, w, w))
            else -> face
        }
    }

    // a face and its cap, what casts the shadow: a rectangle the two boxes'; a path its cap's (one outline, not
    // two overlapping, which even-odd would leave a hole in), the face over the rest of it
    private fun outline(face: Shape, cap: Shape): Shape = when (face) {
        is Shape.Rect -> Shape.Rect(face.box.union(cap.bounds), face.radii)
        is Shape.Path -> cap
    }

    // [p] with each point kept in [box]: a path's shadow never on the row below
    private fun within(p: Shape.Path, box: Box) = Shape.Path(
        p.ops.map {
            when (it) {
                is Shape.Path.Op.MoveTo -> Shape.Path.Op.MoveTo(it.x.coerceIn(box.left, box.right), it.y.coerceIn(box.top, box.bottom))
                is Shape.Path.Op.LineTo -> Shape.Path.Op.LineTo(it.x.coerceIn(box.left, box.right), it.y.coerceIn(box.top, box.bottom))
                Shape.Path.Op.Close -> it
            }
        },
    )

    /** [r] in px for [box]: each corner no more than half its shorter side, none where it has no size. */
    fun radii(r: Radius, box: Box, density: Double): List<Double> {
        val max = maxOf(0.0, min(box.width, box.height) / 2)
        return if (r.half) List(CORNERS) { max } else r.corners.map { (it * density).coerceIn(0.0, max) }
    }

    private const val CORNERS = 4
}
