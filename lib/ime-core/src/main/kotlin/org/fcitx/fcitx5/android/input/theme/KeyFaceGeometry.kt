/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.fcitx.fcitx5.android.scene.look.KeyKind
import org.fcitx.fcitx5.android.scene.look.Keys
import org.fcitx.fcitx5.android.scene.look.NineSlice
import kotlin.math.min
import kotlin.math.round

/** Pixels. */
data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val hasSize get() = width > 0 && height > 0
}

/** Half the shorter side at most; 0 for no size (a key squeezed to nothing in split screen). */
fun cornerRadius(radius: Float, width: Float, height: Float): Float =
    radius.coerceIn(0f, maxOf(0f, min(width, height) / 2))

/** [face] null: no face (flat keys but space and Return); [oval]: face and pressed layer are circles (flat Return). */
data class KeyFace(
    val face: Area?,
    val shadow: Area?,
    val border: Float,
    val pressed: Area,
    val radius: Float,
    val oval: Boolean,
)

/** A key's layers from its cell, computed alike by the theme maker; the gap is inside the cell, so the whole cell is pressed. */
object KeyFaceGeometry {

    /** A key's say over the style's: [On] a face on flat keys (确定), [Off] none on blocks (nine keys' column), [Special] space bar or Return circle. */
    enum class Border { Default, On, Off, Special }

    fun of(width: Float, height: Float, kind: KeyKind, keys: Keys, landscape: Boolean, density: Float, margin: Boolean, border: Border): KeyFace =
        when {
            border == Border.On -> of(width, height, kind, keys.copy(blocks = true), landscape, density, margin)
            border == Border.Off -> of(width, height, kind, keys, landscape, density, margin).copy(face = null, shadow = null, border = 0f, oval = false)
            keys.blocks || border == Border.Special -> of(width, height, kind, keys, landscape, density, margin)
            else -> of(width, height, KeyKind.Letter, keys, landscape, density, margin)
        }

    fun of(width: Float, height: Float, kind: KeyKind, keys: Keys, landscape: Boolean, density: Float, margin: Boolean = true): KeyFace {
        val h = if (!margin) 0f else (if (landscape) keys.gap.hLand else keys.gap.h) * density
        val v = if (!margin) 0f else (if (landscape) keys.gap.vLand else keys.gap.v) * density
        val inner = Area(h, v, width - h, height - v)
        if (keys.blocks) {
            val radius = keys.radiusDp * density
            // kept within the cell: never drawn on the row below
            val shadowBottom = (inner.bottom + keys.shadowDp * density).coerceAtMost(height)
            val shadow = if (keys.shadowDp > 0) inner.copy(bottom = shadowBottom) else null
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

/** Corners keep their size; a target smaller than two corners shrinks them alike. Pieces of no size are left out. */
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
        // insets wider than the picture (an imported theme's): the corners' source kept within it
        val (sl, sr) = fit(i.left.toFloat(), i.right.toFloat(), srcWidth.toFloat())
        val (st, sb) = fit(i.top.toFloat(), i.bottom.toFloat(), srcHeight.toFloat())
        val sx = listOf(0f, sl, srcWidth - sr, srcWidth.toFloat())
        val sy = listOf(0f, st, srcHeight - sb, srcHeight.toFloat())
        val dx = listOf(target.left) + inner(dl, dr, target.left, target.right) + target.right
        val dy = listOf(target.top) + inner(dt, db, target.top, target.bottom) + target.bottom
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

    // edges on whole pixels: two anti-aliased pieces meeting mid-pixel show a line across the face
    private fun inner(near: Float, far: Float, lo: Float, hi: Float): List<Float> {
        val x1 = if (near == 0f) lo else round(lo + near).coerceIn(lo, hi)
        return listOf(x1, if (far == 0f) hi else round(hi - far).coerceIn(x1, hi))
    }
}

/** The picture laid as CENTER_CROP; a key showing it shows what is under it, wherever it is. */
object PictureWindow {

    data class Placement(val scale: Float, val dx: Float, val dy: Float)

    fun centerCrop(picWidth: Int, picHeight: Int, boardWidth: Float, boardHeight: Float): Placement {
        val scale = maxOf(boardWidth / picWidth, boardHeight / picHeight)
        return Placement(scale, (boardWidth - picWidth * scale) / 2, (boardHeight - picHeight * scale) / 2)
    }

    /** Over an animated picture keys are see-through: how dark to make it to show at [brightness]; darkened only. */
    fun seeThroughDarken(board: Int, brightness: Int): Int =
        if (board <= 0 || brightness >= board) 0 else PERCENT - brightness * PERCENT / board

    private const val PERCENT = 100

    fun under(p: Placement, area: Area) = Area(
        (area.left - p.dx) / p.scale,
        (area.top - p.dy) / p.scale,
        (area.right - p.dx) / p.scale,
        (area.bottom - p.dy) / p.scale,
    )
}
