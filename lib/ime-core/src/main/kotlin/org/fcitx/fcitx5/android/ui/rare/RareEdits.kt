/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.rare

import org.fcitx.fcitx5.android.engine.data.CharLevels
import org.fcitx.fcitx5.android.engine.data.CharLevels.Level
import org.fcitx.fcitx5.android.engine.user.RareChoice
import org.fcitx.fcitx5.android.engine.user.RareChoice.Change
import org.fcitx.fcitx5.android.engine.user.RareGrid

/**
 * 生僻字屏蔽's page as edited (dev/design/061-rare-char-block.md §6.2): the choice as read, with the
 * page's [changes] laid over it as they are made; the [changes] saved as they were made when the page
 * stops (RareCharsManager.update lays them over the file as it is then). Each change but the switch
 * can be taken back ([undo]): a group's box and a drag change thousands at once.
 */
class RareEdits(private val grid: RareGrid, private val levels: CharLevels, read: RareChoice) {

    /** The choice as the page shows it. */
    var choice: RareChoice = read
        private set

    private val made = ArrayList<Change>()

    /** What the page changed, in order: none, nothing to save. */
    val changes: List<Change> get() = made

    /** Whether the file is a later version's: nothing may change (§4.2). */
    val readOnly: Boolean get() = choice.newer

    // how many times the edits were [saved]: a step taken before one is no more to take back
    private var saves = 0

    /** A step to take back: the choice before it and how many changes there were. */
    class Undo internal constructor(internal val before: RareChoice, internal val count: Int, internal val saves: Int)

    private fun apply(vararg changes: Change): Undo {
        check(!readOnly) { "a later version's file" }
        val undo = Undo(choice, made.size, saves)
        choice = choice.with(changes.asList(), levels)
        made += changes
        return undo
    }

    fun setOn(on: Boolean) {
        apply(Change.On(on))
    }

    /** Whether the cell [i] is held back. */
    fun held(i: Int): Boolean = choice.holds(grid.chars[i], grid.level(i))

    /** The cell [i] the other way. */
    fun toggle(i: Int): Undo = apply(Change.One(grid.chars[i], !held(i)))

    /** The cells [cells] (grid indexes) all [held] or let through: one undo for them all. */
    fun set(cells: Collection<Int>, held: Boolean): Undo = apply(*cells.map { Change.One(grid.chars[it], held) }.toTypedArray())

    /** [level]'s group, every character of it, [held] or let through. */
    fun setGroup(level: Level, held: Boolean): Undo = apply(Change.Group(level, held))

    /** 恢复默认: 扩展 B alone, no exceptions. */
    fun reset(): Undo = apply(Change.Reset)

    /** Takes [undo]'s step back; false, nothing done, for one made before the last save. */
    fun undo(undo: Undo): Boolean {
        if (undo.saves != saves) return false
        choice = undo.before
        while (made.size > undo.count) made.removeAt(made.size - 1)
        return true
    }

    /**
     * The first [sent] changes saved, the file then [file]: the changes made since (the page made
     * anew while the save ran) laid over it, still to save.
     */
    fun saved(file: RareChoice, sent: Int) {
        val later = made.drop(sent)
        made.clear()
        made += later
        choice = file.with(later, levels)
        saves++
    }

    /** A group's box: all its cells held, some, or none. */
    enum class Box { ALL, SOME, NONE }

    // the cells held by level (its ordinal), counted in one pass once a choice: the page asks a dozen times a step,
    // each a pass over some 35,000 cells (R26 F8251)
    private var countedFor: RareChoice? = null
    private var counts = IntArray(0)

    private fun counts(): IntArray {
        val c = choice
        if (countedFor !== c) {
            val n = IntArray(Level.entries.size)
            for (i in 0 until grid.size) if (held(i)) n[grid.level(i).ordinal]++
            counts = n
            countedFor = c
        }
        return counts
    }

    /** How many of [level]'s cells are held. */
    fun heldIn(level: Level): Int = counts()[level.ordinal]

    fun box(level: Level): Box {
        val held = heldIn(level)
        return when (held) {
            0 -> Box.NONE
            grid.count(level) -> Box.ALL
            else -> Box.SOME
        }
    }

    /** 「已屏蔽 N 个字」: the cells held, off counting none, whatever the phone can draw (§6.1). */
    fun heldCount(): Int = if (!choice.on || choice.newer) 0 else counts().sum()

    companion object {
        private const val ASCII = 0x7F

        /** The settings list's subtitle count: [heldCount] of the choice as read. */
        fun heldCount(grid: RareGrid, levels: CharLevels, choice: RareChoice): Int = RareEdits(grid, levels, choice).heldCount()

        /** What a search for [query] finds (§6.2): a spelling's cells, or the character pasted. */
        fun search(query: String, grid: RareGrid, levels: CharLevels, sorted: Map<Level, IntArray>): Found {
            val q = query.trim()
            if (q.isEmpty()) return Found.Cells(emptyList())
            val cp = q.codePointAt(0)
            // one character not a Latin letter: pasted, found as itself; else a spelling
            if (Character.charCount(cp) == q.length && cp > ASCII) {
                return when (val level = levels.of(cp)) {
                    null -> Found.NotOurs(cp)
                    Level.L1, Level.L2 -> Found.Common(cp)
                    else -> grid.indexOf(cp).let { if (it >= 0) Found.Cells(listOf(it)) else Found.NoCell(cp, level) }
                }
            }
            return Found.Cells(grid.find(q, sorted))
        }
    }

    /** What a search found. */
    sealed class Found {
        /** Cells to show (grid indexes): none, nothing read so. */
        data class Cells(val cells: List<Int>) : Found()

        /** 一级 or 二级: 「常用字，不能屏蔽」. */
        data class Common(val codePoint: Int) : Found()

        /** Not this page's: 「不是这里管的字」. */
        data class NotOurs(val codePoint: Int) : Found()

        /** Its group's, but no reading known: no cell, held with its group. */
        data class NoCell(val codePoint: Int, val level: Level) : Found()
    }
}
