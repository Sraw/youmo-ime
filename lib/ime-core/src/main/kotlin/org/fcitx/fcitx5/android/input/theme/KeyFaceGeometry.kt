/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import kotlin.math.min

/** A rectangle, pixels. */
data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val hasSize get() = width > 0 && height > 0
}

/**
 * Where a key's layers go in its cell: the face, the shadow under it, the border's width, the
 * pressed layer. [face] null: no face (flat keys but the space and Return); [oval] the face and the
 * pressed layer are circles (flat Return). [radius] for the face, the shadow and the pressed layer.
 */
data class KeyFace(
    val face: Area?,
    val shadow: Area?,
    val border: Float,
    val pressed: Area,
    val radius: Float,
    val oval: Boolean,
)

/**
 * A key's layers from its cell ([width]×[height] px, at [density] px a dp) and the theme's [keys]:
 * what KeyView drew by hand before 3.0 (its shadowed and bordered faces, a flat space's bar and
 * Return's circle), in one place the theme maker computes the same. The gap is inside the cell, so
 * the whole cell is pressed; [margin] false, a key drawn to its cell's edges.
 */
object KeyFaceGeometry {

    fun of(width: Float, height: Float, kind: KeyKind, keys: Keys, landscape: Boolean, density: Float, margin: Boolean = true): KeyFace {
        val h = if (!margin) 0f else (if (landscape) keys.gap.hLand else keys.gap.h) * density
        val v = if (!margin) 0f else (if (landscape) keys.gap.vLand else keys.gap.v) * density
        val inner = Area(h, v, width - h, height - v)
        if (keys.blocks) {
            val radius = keys.radiusDp * density
            val shadow = if (keys.shadowDp > 0) inner.copy(bottom = inner.bottom + keys.shadowDp * density) else null
            return KeyFace(inner, shadow, keys.borderDp * density, inner, radius, oval = false)
        }
        return when (kind) {
            KeyKind.Space -> {
                val minHeight = SPACE_MIN_HEIGHT_DP * density
                val hInset = SPACE_H_INSET_DP * density
                val vInset = if (height < minHeight) 0f else min((height - minHeight) / 2, SPACE_MAX_V_INSET_DP * density)
                val bar = Area(hInset, vInset, width - hInset, height - vInset)
                KeyFace(bar, null, 0f, bar, SPACE_RADIUS_DP * density, oval = false)
            }
            KeyKind.Enter -> {
                val size = min(min(width, height), ENTER_MAX_DP * density)
                val left = (width - size) / 2
                val top = (height - size) / 2
                val circle = Area(left, top, left + size, top + size)
                KeyFace(circle, null, 0f, circle, size / 2, oval = true)
            }
            else -> KeyFace(null, null, 0f, inner, 0f, oval = false)
        }
    }

    private const val SPACE_MIN_HEIGHT_DP = 26
    private const val SPACE_H_INSET_DP = 10
    private const val SPACE_MAX_V_INSET_DP = 16
    private const val SPACE_RADIUS_DP = 3
    private const val ENTER_MAX_DP = 35
}

/**
 * A nine-slice image ([NineSlice]) laid in [target]: the pieces of the source ([srcWidth]×[srcHeight])
 * and where each is drawn. Corners keep their size (insets / pxPerDp dp); a target smaller than two
 * corners together has them shrunk alike, so they meet. Pieces of no size are left out.
 */
object NineSliceLayout {

    data class Piece(val src: Area, val dst: Area)

    fun of(srcWidth: Int, srcHeight: Int, slice: NineSlice, target: Area, density: Float): List<Piece> {
        val i = slice.insets
        if (i.isZero) {
            return listOf(Piece(Area(0f, 0f, srcWidth.toFloat(), srcHeight.toFloat()), target))
        }
        val scale = density / slice.pxPerDp
        val (dl, dr) = fit(i.left * scale, i.right * scale, target.width)
        val (dt, db) = fit(i.top * scale, i.bottom * scale, target.height)
        val sx = listOf(0f, i.left.toFloat(), (srcWidth - i.right).toFloat(), srcWidth.toFloat())
        val sy = listOf(0f, i.top.toFloat(), (srcHeight - i.bottom).toFloat(), srcHeight.toFloat())
        val dx = listOf(target.left, target.left + dl, target.right - dr, target.right)
        val dy = listOf(target.top, target.top + dt, target.bottom - db, target.bottom)
        val out = ArrayList<Piece>(9)
        for (r in 0..2) for (c in 0..2) {
            val src = Area(sx[c], sy[r], sx[c + 1], sy[r + 1])
            val dst = Area(dx[c], dy[r], dx[c + 1], dy[r + 1])
            if (src.hasSize && dst.hasSize) out += Piece(src, dst)
        }
        return out
    }

    private fun fit(a: Float, b: Float, room: Float): Pair<Float, Float> {
        if (a + b <= room) return a to b
        val k = room / (a + b)
        return a * k to b * k
    }
}

/**
 * The theme's picture under the keyboard as an ImageView's CENTER_CROP lays it ([picWidth]×[picHeight]
 * over a [boardWidth]×[boardHeight] keyboard), and which part of the picture a key or the bar at an
 * [Area] of the keyboard shows: a key showing the picture shows what is under it, wherever it is.
 */
object PictureWindow {

    /** The scale and the offset the picture is drawn at. */
    data class Placement(val scale: Float, val dx: Float, val dy: Float)

    fun centerCrop(picWidth: Int, picHeight: Int, boardWidth: Float, boardHeight: Float): Placement {
        val scale = maxOf(boardWidth / picWidth, boardHeight / picHeight)
        return Placement(scale, (boardWidth - picWidth * scale) / 2, (boardHeight - picHeight * scale) / 2)
    }

    /** The picture's pixels under [area] of the keyboard. */
    fun under(p: Placement, area: Area) = Area(
        (area.left - p.dx) / p.scale,
        (area.top - p.dy) / p.scale,
        (area.right - p.dx) / p.scale,
        (area.bottom - p.dy) / p.scale,
    )
}
