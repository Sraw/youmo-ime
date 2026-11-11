/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.web

import org.fcitx.fcitx5.android.scene.Box
import kotlin.math.round

/**
 * A nine-slice image cut as the app cuts it (ime-core's NineSliceLayout, which the app's ScenePainter
 * draws by): its corners [left]…[bottom] source pixels at [pxPerDp], drawn at the screen's [density];
 * corners wider than the target, or than the image, shrunk alike to fit; no insets, the whole image.
 */
internal object NineSliceCut {
    data class Piece(val src: Box, val dst: Box)

    @Suppress("LongParameterList") // the insets as the scene's op has them
    fun of(srcWidth: Double, srcHeight: Double, left: Int, top: Int, right: Int, bottom: Int, pxPerDp: Int, target: Box, density: Double): List<Piece> {
        if (listOf(left, top, right, bottom).all { it == 0 }) return listOf(Piece(Box(0.0, 0.0, srcWidth, srcHeight), target))
        val scale = density / pxPerDp
        val (dl, dr) = fit(left * scale, right * scale, target.width)
        val (dt, db) = fit(top * scale, bottom * scale, target.height)
        val (sl, sr) = fit(left.toDouble(), right.toDouble(), srcWidth)
        val (st, sb) = fit(top.toDouble(), bottom.toDouble(), srcHeight)
        val sx = listOf(0.0, sl, srcWidth - sr, srcWidth)
        val sy = listOf(0.0, st, srcHeight - sb, srcHeight)
        val dx = listOf(target.left) + inner(dl, dr, target.left, target.right) + target.right
        val dy = listOf(target.top) + inner(dt, db, target.top, target.bottom) + target.bottom
        val out = ArrayList<Piece>(PIECES)
        for (r in 0..2) {
            for (c in 0..2) {
                val src = Box(sx[c], sy[r], sx[c + 1], sy[r + 1])
                val dst = Box(dx[c], dy[r], dx[c + 1], dy[r + 1])
                if (src.hasSize && dst.hasSize) out += Piece(src, dst)
            }
        }
        return out
    }

    private fun fit(a: Double, b: Double, room: Double): Pair<Double, Double> {
        if (a + b <= room) return a to b
        val k = room / (a + b)
        return a * k to b * k
    }

    // the edges between pieces on whole pixels, as the app's: two pieces meeting mid-pixel leave a seam;
    // a side with no inset keeps the target's own edge
    private fun inner(near: Double, far: Double, lo: Double, hi: Double): List<Double> {
        val x1 = if (near == 0.0) lo else round(lo + near).coerceIn(lo, hi)
        return listOf(x1, if (far == 0.0) hi else round(hi - far).coerceIn(x1, hi))
    }

    private const val PIECES = 9
}
