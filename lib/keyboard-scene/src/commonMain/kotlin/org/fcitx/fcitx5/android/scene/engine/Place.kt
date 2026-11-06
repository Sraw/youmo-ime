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

/** An item where it goes: its [cell], and its [hit] (a key's: its cell, a row's blank end included). */
class Placed(val item: Item, val copy: Int, val cell: Box, val hit: Box?)

/**
 * Step 3 of §6 for `arrange="rows"`, in the [board] (the keyboard's area, or the part of it a board
 * one-handed or at a side has: rules D §2): rows one under another, as tall as their `height=` shares
 * of the board (rules D §1: equal by default); in a row, keys side by side, as wide as their units of
 * the board's `columns=`, `fill` sharing what is left; a row narrower than the board centred (rules
 * A §1: 双拼's second row without `;`, 0.05 each side).
 *
 * An item `rows="n"` (the nine keys' column, the handwriting pad: rules C §2, §8) is as tall as its
 * row and the n − 1 under it, and comes first or last in its row; the rows under it are laid out in
 * what it leaves them, as if their side of the board ended at its edge.
 *
 * The touch area (060-render-v2 §3, user's P0 decision): a key's whole cell, the gap inside it
 * included (rules B §2), and a row's blank ends belong to its end keys (A and L), so no touch on a row
 * falls on nothing but a `<gap>`. `expand_keypress_area` goes: that is what it did, now always.
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

    private enum class Side { Left, Right }

    // what an item across rows keeps of the rows under it: [left, right) px, down to row [last]
    private class Kept(val side: Side, val left: Double, val right: Double, val last: Int)

    fun rows(
        rows: List<ExpandedRow>,
        columns: Double,
        onSplit: OnSplit,
        env: Env,
        board: Box = Box(0.0, 0.0, env.width, env.height),
        warnings: Problems = Problems(),
    ): Parsed<List<Placed>> {
        val p = Problems()
        val total = rows.sumOf { it.height }
        val unit = board.width / columns
        val sizes = if (env.split && onSplit == OnSplit.Split) SplitKeyboard.Sizes((board.width / env.density + EPSILON).toInt()) else null
        val bottoms = ArrayList<Double>()
        var y = board.top
        rows.forEachIndexed { r, row ->
            y = if (r == rows.lastIndex) board.bottom else y + board.height * row.height / total
            bottoms += y
        }
        val out = ArrayList<Placed>()
        val kept = ArrayList<Kept>()
        rows.forEachIndexed { r, row ->
            val top = if (r == 0) board.top else bottoms[r - 1]
            kept.removeAll { it.last < r }
            val lo = kept.filter { it.side == Side.Left }.maxOfOrNull { it.right } ?: board.left
            val hi = kept.filter { it.side == Side.Right }.minOfOrNull { it.left } ?: board.right
            val room = (hi - lo) / unit
            val laid = if (sizes == null) Laid(row.items.map { Triple(it, 0, it.width) }, row.indent, row.endIndent) else split(row, sizes, columns, warnings)
            val widths = laid.items
            val fixed = widths.sumOf { (it.third as? Width.Units)?.units ?: 0.0 } + laid.indent + laid.endIndent
            val fills = widths.count { it.third == Width.Fill }
            if (fixed > room + EPSILON) p.add(row.line, "the row is ${SceneText.n(fixed)} units wide here, over the ${SceneText.n(room)} it has")
            val fillUnits = if (fills > 0) maxOf(0.0, room - fixed) / fills else 0.0
            val used = fixed + fillUnits * fills
            // what no fill takes, half each side (a row's own indents on top)
            var x = lo + ((room - used) / 2 + laid.indent) * unit
            val cells = widths.mapIndexed { i, (item, copy, width) ->
                val w = (if (width is Width.Units) width.units else fillUnits) * unit
                val n = item.rows
                val bottom = if (n > 1) span(n, r, i, widths.size, bottoms, row.line, p) else bottoms[r]
                if (n > 1 && width !is Width.Units) p.add(row.line, "an item across rows needs a width in units, not fill")
                val cell = Box(x, top, x + w, bottom)
                if (n > 1) kept += Kept(if (i == 0) Side.Left else Side.Right, cell.left, cell.right, minOf(r + n - 1, rows.lastIndex))
                x += w
                Triple(item, copy, cell)
            }
            cells.forEachIndexed { i, (item, copy, cell) ->
                val hit = if (item !is ExpandedKey) {
                    null
                } else {
                    Box(if (i == 0) lo else cell.left, cell.top, if (i == cells.lastIndex) hi else cell.right, cell.bottom)
                }
                out += Placed(item, copy, cell, hit)
            }
        }
        return p.result { out }
    }

    // the bottom of an item [n] rows tall at [i] of [count] in row [r]: first or last in its row, the rows there
    private fun span(n: Int, r: Int, i: Int, count: Int, bottoms: List<Double>, line: Int, p: Problems): Double {
        if (i != 0 && i != count - 1) p.add(line, "an item across rows must come first or last in its row")
        if (r + n - 1 > bottoms.lastIndex) p.add(line, "rows=\"$n\" but ${bottoms.size - r} rows from here")
        return bottoms[minOf(r + n - 1, bottoms.lastIndex)]
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
        if (halves.size != 2 * n && halves.size != ODD_HALVES) {
            warnings.add(row.line, "split=\"halves\" with ${halves.size} keys here, not 9 or 10: the row is scaled")
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
