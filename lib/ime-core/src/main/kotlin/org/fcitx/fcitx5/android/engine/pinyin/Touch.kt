/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.E
import kotlin.math.log10

/**
 * Where a key was tapped, as the keyboard saw it (061-neighbour-correction §2.2): [dx] and [dy]
 * from the key's centre in its width and height (±0.5 its edges; past them where the key's touch
 * area is wider than it is drawn), the key [widthDp] by [heightDp], and the keys around it [near].
 * The keyboard's geometry stays the keyboard's: each key carries its own neighbours, so a layout
 * of the user's, a split or one-handed keyboard read alike.
 */
data class Touch(val dx: Float, val dy: Float, val widthDp: Float, val heightDp: Float, val near: List<Near> = emptyList()) {
    /** The key around this one that types [c]; null where none does. */
    fun near(c: Char): Near? = near.firstOrNull { it.char == c }

    /**
     * As text, for an evaluation set's column (ime-eval writes it, the device's runner reads it):
     * `dx,dy,w,h`, then `|` and each key around it, its character and `,nx,ny,nw,nh`.
     */
    fun text(): String = buildString {
        append(dx).append(',').append(dy).append(',').append(widthDp).append(',').append(heightDp)
        for (n in near) append('|').append(n.char).append(',').append(n.nx).append(',').append(n.ny).append(',').append(n.nw).append(',').append(n.nh)
    }

    companion object {
        /** [text]'s touch; null where it is none. */
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

/**
 * A key around a tapped one: what it types ([char], a grouped keyboard's code), its centre [nx],
 * [ny] from the tapped key's, and its size [nw] by [nh], all in the tapped key's width and height.
 */
data class Near(val char: Char, val nx: Float, val ny: Float, val nw: Float = 1f, val nh: Float = 1f)

/**
 * What reading a tapped key as a key around it costs (log10, as the model's scores; §3): the log
 * of how much likelier the tap is under a finger aimed at that key than at the one hit, each a
 * round Gaussian of [sigmaDp] (a finger misses by the same whatever the key's size), never above
 * 0 (a slip is never likelier than the key hit, a wide key next to a narrow one or a tap past the
 * key's edge as well), [legal] more where the keys as typed read too, and none below [floor].
 * [fixed]: a key with no place (a hardware keyboard's): the 0.6.0 rule, a slip read only where
 * the keys spell nothing.
 */
data class SlipCost(
    val sigmaDp: Float = 10f,
    val floor: Float = -4f,
    val legal: Float = -2f,
    val fixed: Float = -2f,
) {
    init {
        // T9Segmenter.passing drops a key around a tap that fails the floor as an illegal slip, for the legal one too
        require(legal <= 0f) { "a legal slip never costs less than an illegal one" }
    }

    /** What reading [touch] as [near] costs, [legal] or not; NaN where it is below [floor]. */
    fun of(touch: Touch, near: Near, legal: Boolean): Float {
        val sx = sigmaDp / touch.widthDp
        val sy = sigmaDp / touch.heightDp
        val ex = touch.dx - near.nx
        val ey = touch.dy - near.ny
        val raw = -HALF_LOG10_E * ((ex * ex - touch.dx * touch.dx) / (sx * sx) + (ey * ey - touch.dy * touch.dy) / (sy * sy))
        val cost = minOf(0f, raw) + if (legal) this.legal else 0f
        return if (cost >= floor) cost else Float.NaN
    }

    /** [of] a legal slip, [illegal] what the same tap costs read so as an illegal one (NaN: below [floor] already). */
    fun legal(illegal: Float): Float = (illegal + legal).let { if (it >= floor) it else Float.NaN }

    private companion object {
        val HALF_LOG10_E = (0.5 * log10(E)).toFloat()
    }
}

/**
 * A keyboard's typing keys, each [Box] (in dp, the keyboard's own coordinates) under what it types:
 * the [Touch] of a tap in one, with the keys around it (the next ones in its row either side, and
 * those of the rows above and below that overlap it across; 061-neighbour-correction §2.2). By
 * where the keys are, not a layout's name: a layout of the user's, a split or one-handed keyboard
 * read alike; a gap between keys is no gap between neighbours.
 */
class KeyBoxes(private val keys: Map<Char, Box>) {

    /** A key's rectangle. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val cx get() = (left + right) / 2
        val cy get() = (top + bottom) / 2
        val w get() = right - left
        val h get() = bottom - top
    }

    private val rows by lazy { rowsOf(keys) }

    // a key's neighbours, worked out when first asked: a tap asks for its key's alone
    // (TouchBoxes' boards are shared, their taps made on any thread)
    private val around = ConcurrentHashMap<Char, List<Near>>()

    private fun aroundOf(c: Char): List<Near> = if (c !in keys) emptyList() else around.computeIfAbsent(c) { aroundFresh(it) }

    private fun aroundFresh(c: Char): List<Near> {
        val k = keys.getValue(c)
        val r = rows.indexOfFirst { row -> c in row }
        val row = rows[r]
        val out = ArrayList<Near>()
        row.lastOrNull { keys.getValue(it).cx < k.cx }?.let { out += near(it, k) }
        row.firstOrNull { keys.getValue(it).cx > k.cx }?.let { out += near(it, k) }
        for (other in listOfNotNull(rows.getOrNull(r - 1), rows.getOrNull(r + 1))) {
            for (d in other) {
                val o = keys.getValue(d)
                if (minOf(o.right, k.right) - maxOf(o.left, k.left) > OVERLAP * k.w) out += near(d, k)
            }
        }
        return out.toList()
    }

    private fun near(d: Char, k: Box): Near {
        val o = keys.getValue(d)
        return Near(d, (o.cx - k.cx) / k.w, (o.cy - k.cy) / k.h, o.w / k.w, o.h / k.h)
    }

    /** What a tap at ([x], [y]) on the key typing [hit] is: null where no key types it. */
    fun touch(hit: Char, x: Float, y: Float): Touch? {
        val k = keys[hit] ?: return null
        return Touch((x - k.cx) / k.w, (y - k.cy) / k.h, k.w, k.h, aroundOf(hit))
    }

    /** The keys around the one typing [c], as [touch] has them. */
    fun around(c: Char): List<Near> = aroundOf(c)

    companion object {
        /**
         * The [Touch] of a tap at ([x], [y]) on the [hit]th of [keys], each what it types and where: a
         * character typed by more than one key (a split keyboard's G on both halves, a layout's two
         * keys of one letter) is the copy nearest the key hit, the key hit its own.
         */
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

        // keys of rows above and below overlapping by less than this much of the key's width are no neighbours (a corner)
        private const val OVERLAP = 0.01f

        // keys whose centres are within this much of a key's height of each other share a row
        private const val SAME_ROW = 0.25f

        /** The keys in rows, top to bottom, each row left to right. */
        private fun rowsOf(keys: Map<Char, Box>): List<List<Char>> {
            val rows = ArrayList<MutableList<Char>>()
            for (c in keys.keys.sortedBy { keys.getValue(it).cy }) {
                val b = keys.getValue(c)
                val row = rows.lastOrNull()
                if (row != null && b.cy - keys.getValue(row[0]).cy < SAME_ROW * b.h) row += c else rows += mutableListOf(c)
            }
            return rows.map { row -> row.sortedBy { keys.getValue(it).cx } }
        }
    }
}
