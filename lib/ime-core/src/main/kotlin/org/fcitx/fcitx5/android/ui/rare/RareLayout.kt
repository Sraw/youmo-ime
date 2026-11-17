/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.rare

import org.fcitx.fcitx5.android.engine.data.CharLevels.Level
import org.fcitx.fcitx5.android.engine.user.RareChoice

/**
 * The rows of 生僻字屏蔽's grid (dev/design/061-rare-char-block.md §6.2–§6.4): each group's head, then,
 * if open, a row for each first letter and its cells. A group opened from its card (061-settings-cards.md
 * §3.2) is alone and has no head: the page's title and its fixed strip say which it is. Plain arrays, ~35,000 cells: a position's row
 * kind, span index and row are found by a binary search over the blocks (a head or a letter and the
 * cells after it), not by counting from the top as GridLayoutManager's default lookup does.
 */
class RareLayout(groups: List<Group>) {

    /**
     * A group as the page has it: its [cells] (grid indexes, in the grid's order), each one's first
     * [letter], [open] or shut, with a [head] row or not.
     */
    class Group(val level: Level, val cells: IntArray, val letter: (Int) -> Char, val open: Boolean, val head: Boolean = true)

    enum class Kind { HEAD, LETTER, CELL }

    // each block: where it starts, its row's kind, its group, its letter (heads: ' '), how many cells after it
    private val blockStart: IntArray
    private val blockKind: Array<Kind>
    private val blockLevel: Array<Level>
    private val blockLetter: CharArray
    private val blockCells: IntArray

    // the grid index of each cell, by its place among all cells, and each block's first in it
    private val cellIndex: IntArray
    private val blockFirstCell: IntArray

    /** How many rows the list has. */
    val size: Int

    init {
        val starts = ArrayList<Int>()
        val kinds = ArrayList<Kind>()
        val levels = ArrayList<Level>()
        val letters = ArrayList<Char>()
        val counts = ArrayList<Int>()
        val firsts = ArrayList<Int>()
        val cells = ArrayList<Int>()
        var at = 0
        fun block(kind: Kind, level: Level, letter: Char, from: Int, to: Int, of: IntArray) {
            starts += at
            kinds += kind
            levels += level
            letters += letter
            counts += to - from
            firsts += cells.size
            for (i in from until to) cells += of[i]
            at += 1 + to - from
        }
        for (g in groups) {
            if (g.head) block(Kind.HEAD, g.level, ' ', 0, 0, g.cells)
            if (!g.open) continue
            var i = 0
            while (i < g.cells.size) {
                val letter = g.letter(g.cells[i])
                var j = i + 1
                while (j < g.cells.size && g.letter(g.cells[j]) == letter) j++
                block(Kind.LETTER, g.level, letter, i, j, g.cells)
                i = j
            }
        }
        blockStart = starts.toIntArray()
        blockKind = kinds.toTypedArray()
        blockLevel = levels.toTypedArray()
        blockLetter = letters.toCharArray()
        blockCells = counts.toIntArray()
        blockFirstCell = firsts.toIntArray()
        cellIndex = cells.toIntArray()
        size = at
    }

    // the block [position] is in
    private fun blockOf(position: Int): Int {
        require(position in 0 until size) { "row $position of $size" }
        var lo = 0
        var hi = blockStart.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (blockStart[mid] <= position) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun kind(position: Int): Kind {
        val b = blockOf(position)
        return if (position == blockStart[b]) blockKind[b] else Kind.CELL
    }

    /** The group [position] is in. */
    fun level(position: Int): Level = blockLevel[blockOf(position)]

    /** A letter row's letter. */
    fun letter(position: Int): Char = blockLetter[blockOf(position)]

    /** A cell's grid index. */
    fun cell(position: Int): Int {
        val b = blockOf(position)
        check(position != blockStart[b]) { "row $position is no cell" }
        return cellIndex[blockFirstCell[b] + position - blockStart[b] - 1]
    }

    /** A head or a letter takes a row; a cell one of [columns]. */
    fun spanSize(position: Int, columns: Int): Int = if (kind(position) == Kind.CELL) 1 else columns

    /** A cell's column. */
    fun spanIndex(position: Int, columns: Int): Int {
        val b = blockOf(position)
        return if (position == blockStart[b]) 0 else (position - blockStart[b] - 1) % columns
    }

    // the rows before each block, for [rowsFor]'s columns
    private var rowsFor = -1
    private var rowsBefore = IntArray(0)

    /** The screen row [position] is on. */
    fun spanGroupIndex(position: Int, columns: Int): Int {
        if (rowsFor != columns) {
            rowsBefore = IntArray(blockStart.size)
            var rows = 0
            for (b in blockStart.indices) {
                rowsBefore[b] = rows
                rows += 1 + (blockCells[b] + columns - 1) / columns
            }
            rowsFor = columns
        }
        val b = blockOf(position)
        return rowsBefore[b] + if (position == blockStart[b]) 0 else 1 + (position - blockStart[b] - 1) / columns
    }

    /** The letters the fast scroller offers: those of the open groups, a–z. */
    val letters: List<Char> get() = blockLetter.indices.filter { blockKind[it] == Kind.LETTER }.map { blockLetter[it] }.distinct().sorted()

    /**
     * Where the fast scroller at [letter] goes, the list's first row shown being [top]: the row of it in
     * the group [top] is in (a drag in 其它表外字 stays there, review S2), else in the first open group
     * after it (a shut group's head at the top), else in the first open; of the nearest letter before it
     * there where the group has not [letter] (the group's first, before its first); -1 with no group open.
     */
    fun positionOf(letter: Char, top: Int = 0): Int {
        val rows = blockStart.indices.filter { blockKind[it] == Kind.LETTER }
        if (rows.isEmpty()) return -1
        val from = if (top in 0 until size) blockOf(top) else 0
        val level = blockLevel[from].takeIf { l -> rows.any { blockLevel[it] == l } }
            ?: rows.firstOrNull { it > from }?.let { blockLevel[it] }
            ?: blockLevel[rows.first()]
        val group = rows.filter { blockLevel[it] == level }
        val exact = group.firstOrNull { blockLetter[it] == letter }
        val before = group.lastOrNull { blockLetter[it] < letter }
        return blockStart[exact ?: before ?: group.first()]
    }

    companion object {
        /**
         * The groups in the page's order: those held by default first (扩展区), so the page turned on shows a
         * group with characters held at its top; the rest in [Level.GRID]'s order. The file and the grid keep GRID's.
         */
        val ORDER: List<Level> = Level.GRID.sortedBy { it !in RareChoice.DEFAULT }
    }
}
