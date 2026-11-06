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
import kotlin.math.roundToInt

/** An item where it goes: its [cell], and its [hit] (a key's: its cell, a row's blank end included). */
class Placed(val item: Item, val copy: Int, val cell: Box, val hit: Box?)

/**
 * Step 3 of §6 for `arrange="rows"`: rows one under another, as tall as their `height=` shares of
 * the keyboard (rules D §1: equal by default); in a row, keys side by side, as wide as their units of
 * the board's `columns=`, `fill` sharing what is left; a row narrower than the board centred (rules
 * A §1: 双拼's second row without `;`, 0.05 each side).
 *
 * The touch area (060-render-v2 §3, user's P0 decision): a key's whole cell, the gap inside it
 * included (rules B §2), and a row's blank ends belong to its end keys (A and L), so no touch on a row
 * falls on nothing but a `<gap>`. `expand_keypress_area` goes: that is what it did, now always.
 *
 * Split (rules A §8, [OnSplit.Split] boards, the environment splitting): a [RowSplit.Halves] row's
 * keys but those `split="after"` five a side, the middle one on both where there are nine (G, V),
 * each a split letter wide, the room between them filling; the keys after it at their split width.
 * A [RowSplit.Scale] row's keys each as wide in split letters as they were in letters.
 */
object Place {
    private const val EPSILON = 1e-9

    fun rows(rows: List<ExpandedRow>, columns: Double, onSplit: OnSplit, env: Env): Parsed<List<Placed>> {
        val p = Problems()
        val total = rows.sumOf { it.height }
        val unit = env.width / columns
        val sizes = if (env.split && onSplit == OnSplit.Split) SplitKeyboard.Sizes((env.width / env.density).roundToInt()) else null
        val out = ArrayList<Placed>()
        var top = 0.0
        rows.forEachIndexed { r, row ->
            val bottom = if (r == rows.lastIndex) env.height else top + env.height * row.height / total
            val widths = if (sizes == null) row.items.map { Triple(it, 0, it.width) } else split(row, sizes, columns)
            val fixed = widths.sumOf { (it.third as? Width.Units)?.units ?: 0.0 } + row.indent + row.endIndent
            val fills = widths.count { it.third == Width.Fill }
            if (fixed > columns + EPSILON) p.add(row.line, "the row is ${SceneText.n(fixed)} units wide here, over the board's ${SceneText.n(columns)}")
            val fillUnits = if (fills > 0) maxOf(0.0, columns - fixed) / fills else 0.0
            val used = fixed + fillUnits * fills
            // what no fill takes, half each side (a row's own indents on top)
            var x = ((columns - used) / 2 + row.indent) * unit
            val cells = widths.map { (item, copy, width) ->
                val w = (if (width is Width.Units) width.units else fillUnits) * unit
                Triple(item, copy, Box(x, top, x + w, bottom)).also { x += w }
            }
            cells.forEachIndexed { i, (item, copy, cell) ->
                val hit = if (item !is ExpandedKey) {
                    null
                } else {
                    Box(if (i == 0) 0.0 else cell.left, cell.top, if (i == cells.lastIndex) env.width else cell.right, cell.bottom)
                }
                out += Placed(item, copy, cell, hit)
            }
            top = bottom
        }
        return p.result { out }
    }

    // a row's items and widths split: (item, which copy, width)
    private fun split(row: ExpandedRow, sizes: SplitKeyboard.Sizes, columns: Double): List<Triple<Item, Int, Width>> {
        val letter = sizes.letter * columns
        fun scaled(w: Width) = if (w is Width.Units) Width.Units(w.units * letter) else w
        if (row.split == RowSplit.Scale) return row.items.map { Triple(it, 0, scaled(it.width)) }
        val after = row.items.filter { it is ExpandedKey && it.attrs.splitAfter == true }
        val halves = row.items.filter { it !in after && it !is ExpandedGap }
        val n = SplitKeyboard.HALF_KEYS
        val left = halves.take(n)
        val right = halves.takeLast(n)
        val one = Width.Units(letter)
        return left.map { Triple(it, 0, one) } + Triple(SplitRoom, 0, Width.Fill) +
            right.map { Triple(it, if (it in left) 1 else 0, one) } + after.map { Triple(it, 0, scaled(it.width)) }
    }
}

/** The room between a split row's halves: a gap, taking no touch (rules A §8). */
data object SplitRoom : Item {
    override val width: Width get() = Width.Fill
}
