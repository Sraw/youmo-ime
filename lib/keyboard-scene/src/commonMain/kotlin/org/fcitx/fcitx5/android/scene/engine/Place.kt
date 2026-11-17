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

/**
 * An item where it goes: its [cell], and its [hit] (a key's: its cell, a row's blank end included); [head] the part of
 * its cell in its first row, the cell itself but for an item across rows (where a key's bubble goes over it).
 */
class Placed(val item: Item, val copy: Int, val cell: Box, val hit: Box?, val head: Box = cell)

/**
 * Step 3 of §6 for `arrange="rows"`, in the [board] (the keyboard's area, or the part of it a board
 * one-handed or at a side has: rules D §2): rows one under another, as tall as their `height=` shares
 * of the board (rules D §1: equal by default); in a row, keys side by side, as wide as their units of
 * the board's `columns=`, `fill` sharing what is left; a row narrower than the board centred (rules
 * A §1: 双拼's second row without `;`, 0.05 each side).
 *
 * An item `rows="n"` (the nine keys' column, the handwriting pad: rules C §2, §8; a custom layout's tall
 * key, anywhere in its row: 061-custom-layout §18) is as tall as its row and the n − 1 under it; the rows
 * under it are laid out in what it leaves them: their items go left to right over the board, past the
 * room it takes (at a side, as if their side of the board ended at its edge; in the middle, the items
 * after it on its far side), their units and fills counted in the room left. An item that would reach
 * into such room is a problem (an item across rows in its way). The rows counted are those shown here,
 * after `when=`: a row hidden in this environment is not one of its n.
 *
 * The touch area (060-render-v2 §3, user's P0 decision): a key's whole cell, the gap inside it
 * included (rules B §2), and a row's blank ends belong to its end keys (A and L), so no touch on a row
 * falls on nothing but a `<gap>`; beside an item across rows, a stretch's end keys' reach to its edge.
 * `expand_keypress_area` goes: that is what it did, now always.
 *
 * Split (rules A §8, [OnSplit.Split] boards, the environment splitting): a [RowSplit.Halves] row's
 * keys but those `split="after"` five a side, the middle one on both where there are nine (G, V),
 * each a split letter wide, the room between them filling; the keys after it at their split width.
 * Its indents go: the halves are grids from the board's edges. A [RowSplit.Scale] row's keys, and its
 * indents, each as wide in split letters as they were in letters. A halves row of other than nine or
 * ten keys cannot be halved: a [warnings] problem, the row scaled instead.
 */
object Place {
    private const val EPSILON = 1e-9
    private const val ODD_HALVES = 9
    private const val HALF = 0.5

    // what an item across rows keeps of the rows under it: [left, right) px, down to row [last]; [at],
    // [to]: its edges before they were put on whole pixels, what the rows' units are counted in: from the board's
    // edge for one first or last in its row (the rows under it end at its edge, a row's indent beside it theirs too)
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
            // the room the items across rows above leave this row, left to right; its units counted in what is left
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
            // what no fill takes, half each side (a row's own indents on top)
            var x = from + ((room - used) / 2 + laid.indent) * unit
            // where an item starts a stretch between the room kept (the row's first, or the first past an item across rows)
            val starts = BooleanArray(widths.size)
            val cells = widths.mapIndexed { i, (item, copy, width) ->
                val w = (if (width is Width.Units) width.units else fillUnits) * unit
                val at = past(spans, x)
                starts[i] = i == 0 || at > x + EPSILON
                x = at
                // a row too wide for its room said so already
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

    // each row's bottom: its share of the board's height, on a whole pixel ([row]) but the last's, the board's
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

    // a key's touch: a stretch's end keys ([start], [end]) reach to its ends, the board's edge or an item across rows'
    private fun hit(cell: Box, start: Boolean, end: Boolean, spans: List<Kept>, board: Box): Box {
        val left = if (start) spans.filter { it.to < cell.left + EPSILON + 1 }.maxOfOrNull { it.right } ?: board.left else cell.left
        val right = if (end) spans.filter { it.at > cell.right - EPSILON - 1 }.minOfOrNull { it.left } ?: board.right else cell.right
        return Box(left, cell.top, right, cell.bottom)
    }

    // an edge between two rows or two keys on a whole pixel, as the app's views were laid out (Android
    // lays a view out at whole pixels): a key's face, text and icons then where its view drew them, not
    // half a pixel off and rounded the other way (a letter a pixel higher, an edge blurred); the
    // board's own edges stay where they are (a one-handed board's, a split's, may be between pixels)
    private fun pixel(v: Double): Double = floor(v + HALF)

    private fun rowEdge(snap: Boolean): (Double) -> Double = if (snap) ::pixel else { v -> v }

    private fun sideEdge(snap: Boolean, board: Box): (Double) -> Double = if (snap) { x -> edge(x, board) } else { x -> x }

    private fun edge(x: Double, board: Box): Double = if (abs(x - board.left) < EPSILON || abs(x - board.right) < EPSILON) x else pixel(x)

    // [x], or past the room kept it falls in ([spans] left to right; one ending where the next starts, past both)
    private fun past(spans: List<Kept>, x: Double): Double {
        var at = x
        for (s in spans) if (at > s.at - EPSILON && at < s.to - EPSILON) at = s.to
        return at
    }

    // how wide the room kept of [spans] (left to right) is: one inside another's (a key across rows over the side another
    // across more rows keeps) counted once, as 0.6.0 had it
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

    // whether an item from [x], [w] wide, reaches into the room kept of [spans]
    private fun reaches(spans: List<Kept>, x: Double, w: Double): Boolean = spans.any { it.at > x - EPSILON && x + w > it.at + EPSILON }

    // what an item across rows down to row [last] keeps, [cell] at [x], [w] wide, item [i] of a row's [lastIndex]
    private fun keep(cell: Box, last: Int, x: Double, w: Double, i: Int, lastIndex: Int, board: Box): Kept {
        val at = if (i == 0) board.left else x
        val to = if (i != 0 && i == lastIndex) board.right else x + w
        return Kept(cell.left, cell.right, last, at, to)
    }

    // the bottom of an item [n] rows tall in row [r]: the rows there
    private fun span(n: Int, r: Int, bottoms: List<Double>, line: Int, p: Problems): Double {
        if (r + n - 1 > bottoms.lastIndex) p.add(line, "rows=\"$n\" but ${bottoms.size - r} rows from here")
        return bottoms[minOf(r + n - 1, bottoms.lastIndex)]
    }

    /**
     * How many keys a [RowSplit.Halves] row has to halve (its keys but those `split="after"`, its gaps not counted) where
     * that is not 9 or 10: it cannot be halved, and is scaled when split. Null where it can be, or the row scales anyway.
     */
    fun unhalved(row: ExpandedRow): Int? {
        if (row.split != RowSplit.Halves) return null
        val n = row.items.count { it !is ExpandedGap && !(it is ExpandedKey && it.attrs.splitAfter == true) }
        return n.takeIf { it != 2 * SplitKeyboard.HALF_KEYS && it != ODD_HALVES }
    }

    private class Laid(val items: List<Triple<Item, Int, Width>>, val indent: Double, val endIndent: Double)

    // a row's items and widths split: (item, which copy, width), and its indents
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
        // the middle key of nine is on both sides: the right's is its second copy
        val shared = halves.size == ODD_HALVES
        val items = left.map { Triple(it, 0, one) } + Triple(SplitRoom, 0, Width.Fill) +
            right.mapIndexed { i, it -> Triple(it, if (i == 0 && shared) 1 else 0, one) } + after.map { Triple(it, 0, scaled(it.width)) }
        return Laid(items, 0.0, 0.0)
    }
}

/** The room between a split row's halves: a gap, taking no touch (rules A §8). */
data object SplitRoom : Item {
    override val width: Width get() = Width.Fill
}
