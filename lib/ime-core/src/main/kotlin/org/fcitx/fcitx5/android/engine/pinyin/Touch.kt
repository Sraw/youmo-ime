/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.E
import kotlin.math.log10

/** Where a key was tapped: [dx], [dy] from its centre in key widths/heights (±0.5 its edges), its size in dp, and its neighbours [near]; each key carries its own, so any layout reads alike. */
data class Touch(val dx: Float, val dy: Float, val widthDp: Float, val heightDp: Float, val near: List<Near> = emptyList()) {
    fun near(c: Char): Near? = near.firstOrNull { it.char == c }

    /** For an evaluation set's column: `dx,dy,w,h`, then `|` and per neighbour its character and `,nx,ny,nw,nh`. */
    fun text(): String = buildString {
        append(dx).append(',').append(dy).append(',').append(widthDp).append(',').append(heightDp)
        for (n in near) append('|').append(n.char).append(',').append(n.nx).append(',').append(n.ny).append(',').append(n.nw).append(',').append(n.nh)
    }

    companion object {
        fun parse(text: String): Touch? = runCatching {
            val parts = text.split('|')
            val t = fours(parts[0])
            val near = parts.drop(1).map { p ->
                require(p.length > 1 && p[1] == ',')
                val n = fours(p.substring(2))
                Near(p[0], n[0], n[1], n[2], n[3])
            }
            Touch(t[0], t[1], t[2], t[3], near)
        }.getOrNull()

        private fun fours(text: String) = text.split(',').map(String::toFloat).also { require(it.size == 4) }
    }
}

/** A neighbouring key: what it types, its centre and size in the tapped key's units. [stacked]: a layout puts its letters one over another, so places along them run down (not in [Touch.text]). */
data class Near(val char: Char, val nx: Float, val ny: Float, val nw: Float = 1f, val nh: Float = 1f, val stacked: Boolean = false)

/**
 * The log10 cost of reading a tap as a neighbour: how much likelier the tap is aimed at the neighbour than at
 * the key hit, under a round Gaussian of [sigmaDp]; never above 0, [legal] more where the keys as typed read
 * too, none below [floor]. [fixed]: for a key without a place (hardware), slips read only where keys spell nothing.
 */
data class SlipCost(
    val sigmaDp: Float = 10f,
    val floor: Float = -4f,
    val legal: Float = -2f,
    val fixed: Float = -2f,
) {
    init {
        // T9Segmenter.passing drops a neighbour that fails the floor as illegal, for the legal case too
        require(legal <= 0f) { "a legal slip never costs less than an illegal one" }
    }

    /** NaN where below [floor]. */
    fun of(touch: Touch, near: Near, legal: Boolean): Float {
        val sx = sigmaDp / touch.widthDp
        val sy = sigmaDp / touch.heightDp
        val ex = touch.dx - near.nx
        val ey = touch.dy - near.ny
        val raw = -HALF_LOG10_E * ((ex * ex - touch.dx * touch.dx) / (sx * sx) + (ey * ey - touch.dy * touch.dy) / (sy * sy))
        val cost = minOf(0f, raw) + if (legal) this.legal else 0f
        return if (cost >= floor) cost else Float.NaN
    }

    /** A legal slip's cost from its [illegal] cost (NaN already below [floor]). */
    fun legal(illegal: Float): Float = (illegal + legal).let { if (it >= floor) it else Float.NaN }

    private companion object {
        val HALF_LOG10_E = (0.5 * log10(E)).toFloat()
    }
}

/** A keyboard's keys as dp boxes by what they type, giving a tap's [Touch] with neighbours by geometry (row either side, overlapping keys above and below), so any layout reads alike. */
class KeyBoxes(private val keys: Map<Char, Box>) {

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val cx get() = (left + right) / 2
        val cy get() = (top + bottom) / 2
        val w get() = right - left
        val h get() = bottom - top
    }

    private val rows by lazy { rowsOf(keys) }

    // neighbours computed on first ask; boards are shared and taps come from any thread
    private val around = ConcurrentHashMap<Char, List<Near>>()

    private fun aroundOf(c: Char): List<Near> = if (c !in keys) emptyList() else around.computeIfAbsent(c) { aroundFresh(it) }

    private fun aroundFresh(c: Char): List<Near> {
        val k = keys.getValue(c)
        // a key spanning two rows (rows="2") is in both
        val inRows = rows.indices.filter { c in rows[it] }
        val out = LinkedHashMap<Char, Near>()
        for (r in inRows) {
            val row = rows[r]
            row.lastOrNull { keys.getValue(it).cx < k.cx }?.let { out.getOrPut(it) { near(it, k) } }
            row.firstOrNull { keys.getValue(it).cx > k.cx }?.let { out.getOrPut(it) { near(it, k) } }
        }
        for (other in listOfNotNull(rows.getOrNull(inRows.first() - 1), rows.getOrNull(inRows.last() + 1))) {
            for (d in other) {
                val o = keys.getValue(d)
                if (d != c && minOf(o.right, k.right) - maxOf(o.left, k.left) > OVERLAP * k.w) out.getOrPut(d) { near(d, k) }
            }
        }
        return out.values.toList()
    }

    private fun near(d: Char, k: Box): Near {
        val o = keys.getValue(d)
        return Near(d, (o.cx - k.cx) / k.w, (o.cy - k.cy) / k.h, o.w / k.w, o.h / k.h)
    }

    /** Null if no key types [hit]. */
    fun touch(hit: Char, x: Float, y: Float): Touch? {
        val k = keys[hit] ?: return null
        return Touch((x - k.cx) / k.w, (y - k.cy) / k.h, k.w, k.h, aroundOf(hit))
    }

    fun around(c: Char): List<Near> = aroundOf(c)

    companion object {
        /** The [Touch] of a tap on the [hit]th of [keys]; a character on several keys (split keyboard) uses the copy nearest the key hit. */
        fun touch(keys: List<Pair<Char, Box>>, hit: Int, x: Float, y: Float): Touch? {
            val (c, at) = keys.getOrNull(hit) ?: return null
            val nearest = HashMap<Char, Box>()
            for ((d, b) in keys) {
                val kept = nearest[d]
                if (kept == null || apart(b, at) < apart(kept, at)) nearest[d] = b
            }
            nearest[c] = at
            return KeyBoxes(nearest).touch(c, x, y)
        }

        private fun apart(a: Box, b: Box) = (a.cx - b.cx).let { it * it } + (a.cy - b.cy).let { it * it }

        // keys above/below overlapping by less than this share of width are no neighbours (a corner)
        private const val OVERLAP = 0.01f

        // a key belongs to a row whose line passes at least this share of the line key's height inside it
        private const val SAME_ROW = 0.25f

        /** Keys in rows, top to bottom, left to right. A row is a line through the middle of the key that drew it, drawn lowest keys first, so a two-row-high key meets both rows' lines. */
        private fun rowsOf(keys: Map<Char, Box>): List<List<Char>> {
            val lines = ArrayList<Pair<Float, Float>>() // where, and the height of the key that drew it
            val rows = ArrayList<MutableList<Char>>()
            for (c in keys.keys.sortedWith(compareBy({ keys.getValue(it).h }, { keys.getValue(it).cy }))) {
                val b = keys.getValue(c)
                var met = false
                lines.forEachIndexed { i, (y, h) ->
                    if (y > b.top + SAME_ROW * h && y < b.bottom - SAME_ROW * h) {
                        rows[i] += c
                        met = true
                    }
                }
                if (!met) {
                    lines += b.cy to b.h
                    rows += mutableListOf(c)
                }
            }
            return rows.indices.sortedBy { lines[it].first }.map { i -> rows[i].sortedBy { keys.getValue(it).cx } }
        }
    }
}
