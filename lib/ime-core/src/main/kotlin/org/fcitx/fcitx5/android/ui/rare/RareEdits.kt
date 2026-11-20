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

/** 生僻字屏蔽's page as edited: [changes] over the choice read, saved on stop over the file as it is then; each but the switch undoable. */
class RareEdits(private val grid: RareGrid, private val levels: CharLevels, read: RareChoice) {

    var choice: RareChoice = read
        private set

    private val made = ArrayList<Change>()

    /** None: nothing to save. */
    val changes: List<Change> get() = made

    /** A later version's file: nothing may change. */
    val readOnly: Boolean get() = choice.newer

    // a step taken before a save is no more to take back
    private var saves = 0

    /** The choice before it and how many changes there were. */
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

    fun held(i: Int): Boolean = choice.holds(grid.chars[i], grid.level(i))

    fun toggle(i: Int): Undo = apply(Change.One(grid.chars[i], !held(i)))

    /** One undo for them all. */
    fun set(cells: Collection<Int>, held: Boolean): Undo = apply(*cells.map { Change.One(grid.chars[it], held) }.toTypedArray())

    fun setGroup(level: Level, held: Boolean): Undo = apply(Change.Group(level, held))

    /** 恢复默认: 扩展 B alone, no exceptions. */
    fun reset(): Undo = apply(Change.Reset)

    /** False, nothing done, for one made before the last save. */
    fun undo(undo: Undo): Boolean {
        if (undo.saves != saves) return false
        choice = undo.before
        while (made.size > undo.count) made.removeAt(made.size - 1)
        return true
    }

    /** Changes made since (the page made anew while the save ran) laid over [file], still to save. */
    fun saved(file: RareChoice, sent: Int) {
        val later = made.drop(sent)
        made.clear()
        made += later
        choice = file.with(later, levels)
        saves++
    }

    enum class Box { ALL, SOME, NONE }

    // held cells by level, counted once a choice: the page asks a dozen times a step, each over some 35,000 cells
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

    fun heldIn(level: Level): Int = counts()[level.ordinal]

    fun box(level: Level): Box {
        val held = heldIn(level)
        return when (held) {
            0 -> Box.NONE
            grid.count(level) -> Box.ALL
            else -> Box.SOME
        }
    }

    /** 「已屏蔽 N 个字」: off counts none, whatever the phone can draw. */
    fun heldCount(): Int = if (!choice.on || choice.newer) 0 else counts().sum()

    companion object {
        private const val ASCII = 0x7F

        /** The settings list's subtitle count. */
        fun heldCount(grid: RareGrid, levels: CharLevels, choice: RareChoice): Int = RareEdits(grid, levels, choice).heldCount()

        /** A spelling's cells, or the character pasted. */
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

    sealed class Found {
        /** None: nothing read so. */
        data class Cells(val cells: List<Int>) : Found()

        /** 一级 or 二级: 「常用字，不能屏蔽」. */
        data class Common(val codePoint: Int) : Found()

        /** 「不是这里管的字」. */
        data class NotOurs(val codePoint: Int) : Found()

        /** Its group's, but no reading known: no cell, held with its group. */
        data class NoCell(val codePoint: Int, val level: Level) : Found()
    }
}
