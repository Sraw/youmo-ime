/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Problems
import org.fcitx.fcitx5.android.scene.SceneText
import org.fcitx.fcitx5.android.scene.layout.OnSplit
import org.fcitx.fcitx5.android.scene.layout.RowSplit
import org.fcitx.fcitx5.android.scene.layout.Width
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard
import kotlin.math.abs
import kotlin.math.floor

class Placed(val item: Item, val copy: Int, val cell: Box, val hit: Box?, val head: Box = cell)

/**
 * Lays out `arrange="rows"`. The rows under an item across rows are laid out in what it leaves them; a row hidden by
 * `when=` is not one of its n. A key's touch is its whole cell, and a row's blank ends belong to its end keys, so no
 * touch falls on nothing but a `<gap>`. A halves row of other than nine or ten keys cannot be split: it is scaled.
 */
object Place {
    private const val EPSILON = 1e-9
    private const val ODD_HALVES = 9
    private const val HALF = 0.5

    // [at], [to]: the edges before they were put on whole pixels, what the rows' units are counted in
    private class Kept(val left: Double, val right: Double, val last: Int, val at: Double, val to: Double)

    fun rows(
        rows: List<ExpandedRow>,
        columns: Double,
        onSplit: OnSplit,
        env: Env,
        board: Box = Box(0.0, 0.0, env.width, env.height),
        warnings: Problems = Problems(),
        snap: Boolean = true,
    ): Parsed<List<Placed>> {
        // [snap] false: the edges where they fall, what the marks' yields are worked out on (SceneBuilder)
        val row = rowEdge(snap)
        val side = sideEdge(snap, board)
        val p = Problems()
        val unit = board.width / columns
        val sizes = if (env.split && onSplit == OnSplit.Split) SplitKeyboard.Sizes((board.width / env.density + EPSILON).toInt()) else null
        val bottoms = bottoms(rows, board, row)
        val out = ArrayList<Placed>()
        val kept = ArrayList<Kept>()
        rows.forEachIndexed { r, row ->
            val top = if (r == 0) board.top else bottoms[r - 1]
            kept.removeAll { it.last < r }
            val spans = kept.sortedBy { it.at }
            val from = past(spans, board.left)
            val room = (board.width - taken(spans)) / unit
            val laid = if (sizes == null) Laid(row.items.map { Triple(it, 0, it.width) }, row.indent, row.endIndent) else split(row, sizes, columns, warnings)
            val widths = laid.items
            val fixed = widths.sumOf { (it.third as? Width.Units)?.units ?: 0.0 } + laid.indent + laid.endIndent
            val fills = widths.count { it.third == Width.Fill }
            val over = fixed > room + EPSILON
            if (over) p.add(row.line, "the row is ${SceneText.n(fixed)} units wide here, over the ${SceneText.n(room)} it has")
            val fillUnits = if (fills > 0) maxOf(0.0, room - fixed) / fills else 0.0
            val used = fixed + fillUnits * fills
            var x = from + ((room - used) / 2 + laid.indent) * unit
            val starts = BooleanArray(widths.size)
            val cells = widths.mapIndexed { i, (item, copy, width) ->
                val w = (if (width is Width.Units) width.units else fillUnits) * unit
                val at = past(spans, x)
                starts[i] = i == 0 || at > x + EPSILON
                x = at
                if (!over && reaches(spans, x, w)) p.add(row.line, "an item reaches into the room an item across rows above takes")
                val n = item.rows
                if (n > 1 && width !is Width.Units) p.add(row.line, "an item across rows needs a width in units, not fill")
                val cell = Box(side(x), top, side(x + w), span(n, r, bottoms, row.line, p))
                if (n > 1) kept += keep(cell, minOf(r + n - 1, rows.lastIndex), x, w, i, widths.lastIndex, board)
                x += w
                Triple(item, copy, cell)
            }
            cells.forEachIndexed { i, (item, copy, cell) ->
                val hit = if (item is ExpandedKey) hit(cell, starts[i], i == cells.lastIndex || starts[i + 1], spans, board) else null
                val head = if (item.rows > 1) Box(cell.left, cell.top, cell.right, bottoms[r]) else cell
                out += Placed(item, copy, cell, hit, head)
            }
        }
        return p.result { out }
    }

    private fun bottoms(rows: List<ExpandedRow>, board: Box, row: (Double) -> Double): List<Double> {
        val total = rows.sumOf { it.height }
        var y = board.top
        return rows.mapIndexed { r, it ->
            if (r == rows.lastIndex) {
                board.bottom
            } else {
                y += board.height * it.height / total
                row(y)
            }
        }
    }

    private fun hit(cell: Box, start: Boolean, end: Boolean, spans: List<Kept>, board: Box): Box {
        val left = if (start) spans.filter { it.to < cell.left + EPSILON + 1 }.maxOfOrNull { it.right } ?: board.left else cell.left
        val right = if (end) spans.filter { it.at > cell.right - EPSILON - 1 }.minOfOrNull { it.left } ?: board.right else cell.right
        return Box(left, cell.top, right, cell.bottom)
    }

    // on a whole pixel, as Android laid the views out: a letter not a pixel higher, an edge not blurred; the board's own edges stay
    private fun pixel(v: Double): Double = floor(v + HALF)

    private fun rowEdge(snap: Boolean): (Double) -> Double = if (snap) ::pixel else { v -> v }

    private fun sideEdge(snap: Boolean, board: Box): (Double) -> Double = if (snap) { x -> edge(x, board) } else { x -> x }

    private fun edge(x: Double, board: Box): Double = if (abs(x - board.left) < EPSILON || abs(x - board.right) < EPSILON) x else pixel(x)

    private fun past(spans: List<Kept>, x: Double): Double {
        var at = x
        for (s in spans) if (at > s.at - EPSILON && at < s.to - EPSILON) at = s.to
        return at
    }

    // one inside another's counted once, as 0.6.0 had it
    private fun taken(spans: List<Kept>): Double {
        var sum = 0.0
        var end = Double.NEGATIVE_INFINITY
        for (s in spans) {
            val from = maxOf(s.at, end)
            if (s.to > from) sum += s.to - from
            end = maxOf(end, s.to)
        }
        return sum
    }

    private fun reaches(spans: List<Kept>, x: Double, w: Double): Boolean = spans.any { it.at > x - EPSILON && x + w > it.at + EPSILON }

    private fun keep(cell: Box, last: Int, x: Double, w: Double, i: Int, lastIndex: Int, board: Box): Kept {
        val at = if (i == 0) board.left else x
        val to = if (i != 0 && i == lastIndex) board.right else x + w
        return Kept(cell.left, cell.right, last, at, to)
    }

    private fun span(n: Int, r: Int, bottoms: List<Double>, line: Int, p: Problems): Double {
        if (r + n - 1 > bottoms.lastIndex) p.add(line, "rows=\"$n\" but ${bottoms.size - r} rows from here")
        return bottoms[minOf(r + n - 1, bottoms.lastIndex)]
    }

    fun unhalved(row: ExpandedRow): Int? {
        if (row.split != RowSplit.Halves) return null
        val n = row.items.count { it !is ExpandedGap && !(it is ExpandedKey && it.attrs.splitAfter == true) }
        return n.takeIf { it != 2 * SplitKeyboard.HALF_KEYS && it != ODD_HALVES }
    }

    private class Laid(val items: List<Triple<Item, Int, Width>>, val indent: Double, val endIndent: Double)

    private fun split(row: ExpandedRow, sizes: SplitKeyboard.Sizes, columns: Double, warnings: Problems): Laid {
        val letter = sizes.letter * columns
        fun scaled(w: Width) = if (w is Width.Units) Width.Units(w.units * letter) else w
        val scale = Laid(row.items.map { Triple(it, 0, scaled(it.width)) }, row.indent * letter, row.endIndent * letter)
        if (row.split == RowSplit.Scale) return scale
        val after = row.items.filter { it is ExpandedKey && it.attrs.splitAfter == true }
        val halves = row.items.filter { it !in after && it !is ExpandedGap }
        val n = SplitKeyboard.HALF_KEYS
        unhalved(row)?.let {
            warnings.add(row.line, "split=\"halves\" with $it keys here, not 9 or 10: the row is scaled")
            return scale
        }
        val left = halves.take(n)
        val right = halves.takeLast(n)
        val one = Width.Units(letter)
        val shared = halves.size == ODD_HALVES
        val items = left.map { Triple(it, 0, one) } + Triple(SplitRoom, 0, Width.Fill) +
            right.mapIndexed { i, it -> Triple(it, if (i == 0 && shared) 1 else 0, one) } + after.map { Triple(it, 0, scaled(it.width)) }
        return Laid(items, 0.0, 0.0)
    }
}

data object SplitRoom : Item {
    override val width: Width get() = Width.Fill
}
