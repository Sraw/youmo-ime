/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import kotlin.math.max
import kotlin.math.min

/** A rectangle, px. */
data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2
    val centerY: Double get() = (top + bottom) / 2
    val hasSize: Boolean get() = width > 0 && height > 0

    fun inset(left: Double, top: Double, right: Double, bottom: Double) =
        Box(this.left + left, this.top + top, this.right - right, this.bottom - bottom)

    fun moved(dx: Double, dy: Double) = Box(left + dx, top + dy, right + dx, bottom + dy)

    fun union(o: Box) = Box(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))

    fun intersect(o: Box) = Box(max(left, o.left), max(top, o.top), min(right, o.right), min(bottom, o.bottom))

    /** Whether ([x], [y]) is in it: its left and top edges in, its right and bottom out, so boxes that meet share no point. */
    fun contains(x: Double, y: Double): Boolean = x >= left && x < right && y >= top && y < bottom
}

data class Point(val x: Double, val y: Double)

/**
 * What a key is shaped as (060-render-v2 §3.1): a value, not a rectangle written into everything, so a
 * fan of keys later is a [Path] the backends already draw. 0.6.0 makes only [Rect]s.
 */
sealed interface Shape {
    /** The box it fits in: a screen reader's bounds, where a popup goes, what to redraw. */
    val bounds: Box

    /** Whether ([x], [y]) is in it: the hit test (§3.1: a rectangle's fast path, a path's ray cast). */
    fun contains(x: Double, y: Double): Boolean

    /** A rounded rectangle; [radii] its corners' (top left, top right, bottom right, bottom left), px. */
    data class Rect(val box: Box, val radii: List<Double> = NO_RADII) : Shape {
        override val bounds: Box get() = box

        override fun contains(x: Double, y: Double): Boolean {
            if (!box.contains(x, y)) return false
            if (radii.all { it <= 0.0 }) return true
            // in a corner's square but outside its circle: out
            val corners = listOf(
                Triple(box.left + radii[0], box.top + radii[0], radii[0]),
                Triple(box.right - radii[1], box.top + radii[1], radii[1]),
                Triple(box.right - radii[2], box.bottom - radii[2], radii[2]),
                Triple(box.left + radii[3], box.bottom - radii[3], radii[3]),
            )
            return corners.withIndex().none { (i, c) ->
                val (cx, cy, r) = c
                val inCorner = (if (i == 0 || i == 3) x < cx else x > cx) && (if (i < 2) y < cy else y > cy)
                inCorner && (x - cx) * (x - cx) + (y - cy) * (y - cy) > r * r
            }
        }
    }

    /**
     * An outline of straight edges, px: each sub-path [Path.Op.MoveTo] then [Path.Op.LineTo]s, closed.
     * Arcs are left for when a layout arranges keys on one (`arrange="arc"`): a backend's path has them.
     */
    data class Path(val ops: List<Op>) : Shape {
        sealed interface Op {
            data class MoveTo(val x: Double, val y: Double) : Op
            data class LineTo(val x: Double, val y: Double) : Op
            data object Close : Op
        }

        override val bounds: Box
            get() {
                val pts = points().flatten()
                if (pts.isEmpty()) return Box(0.0, 0.0, 0.0, 0.0)
                return Box(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
            }

        // the polygons it outlines
        private fun points(): List<List<Point>> {
            val out = ArrayList<List<Point>>()
            var cur = ArrayList<Point>()
            for (op in ops) {
                when (op) {
                    is Op.MoveTo -> {
                        if (cur.isNotEmpty()) out += cur
                        cur = arrayListOf(Point(op.x, op.y))
                    }
                    is Op.LineTo -> cur += Point(op.x, op.y)
                    Op.Close -> {
                        if (cur.isNotEmpty()) out += cur
                        cur = ArrayList()
                    }
                }
            }
            if (cur.isNotEmpty()) out += cur
            return out
        }

        // even-odd: a ray to the right crosses its edges an odd number of times
        override fun contains(x: Double, y: Double): Boolean {
            var inside = false
            for (poly in points()) {
                for (i in poly.indices) {
                    val a = poly[i]
                    val b = poly[(i + 1) % poly.size]
                    if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
                }
            }
            return inside
        }
    }

    companion object {
        val NO_RADII: List<Double> = listOf(0.0, 0.0, 0.0, 0.0)
    }
}
