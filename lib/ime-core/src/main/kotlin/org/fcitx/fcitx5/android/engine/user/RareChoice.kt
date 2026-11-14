/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.CharLevels
import org.fcitx.fcitx5.android.engine.data.CharLevels.Level
import java.util.BitSet

/**
 * What the user chose of 生僻字屏蔽 (dev/design/061-rare-char-block.md §4.2): [on] or off, the
 * [groups] held back whole, and the characters let through ([allow]) or held back ([block]) apart
 * from their group. A file of its own beside the common words (RareCharsManager):
 *
 * ```
 * # youmo-rare-chars 1
 * on	1
 * groups	EXT
 * allow	𠀀𤝰
 * block	犇
 * ```
 *
 * No file, or one with no `on 1` line, is off. No `groups` line is the default, [Level.EXT] alone
 * (what turning it on the first time writes). A group name or a line this build does not know is
 * kept as it is and written back ([otherGroups], [otherLines]): adding either needs no new version.
 * A file of a later version ([newer]) is off and not written over ([with] refuses).
 *
 * [L1][Level.L1] and [L2][Level.L2] characters are never held back, whatever the file says, nor
 * one [CharLevels] puts in no group.
 */
class RareChoice private constructor(
    val on: Boolean,
    val groups: Set<Level>,
    val allow: Set<Int>,
    val block: Set<Int>,
    val otherGroups: List<String> = emptyList(),
    val otherLines: List<String> = emptyList(),
    val newer: Boolean = false,
) {
    /** Whether [level]'s group is held back whole: never [L1][Level.L1] nor [L2][Level.L2]. */
    fun holdsGroup(level: Level): Boolean = level in Level.GRID && level in groups

    /**
     * Whether [codePoint], in [level], is held back: never a character of the table's first two
     * levels or of no group; else held in [block], let through in [allow] (held if in both), else as its group.
     */
    fun holds(codePoint: Int, level: Level?): Boolean = when {
        level == null || level !in Level.GRID -> false
        codePoint in block -> true
        codePoint in allow -> false
        else -> level in groups
    }

    /** The gate of this choice over [levels]: none while off, of a later version's file, or without [levels]. */
    fun gate(levels: CharLevels?): CharGate {
        if (!on || newer || levels == null) return CharGate.NONE
        val plane0 = BitSet(PLANE)
        if (Level.OUT in groups) CharLevels.OUT_RANGES.forEach { plane0.set(it.first, it.last + 1) }
        // a listed character is its group's, whatever its range says
        for (i in 0 until levels.size) {
            val cp = levels.codePointAt(i)
            if (cp < PLANE) plane0[cp] = holdsGroup(levels.levelAt(i))
        }
        for (cp in allow) if (cp < PLANE && levels.of(cp) in Level.GRID) plane0.clear(cp)
        for (cp in block) if (cp < PLANE && levels.of(cp) in Level.GRID) plane0.set(cp)
        return CharGate.of(plane0) { cp -> holds(cp, levels.of(cp)) }
    }

    /** A change the settings make, laid over the file as it is when saved ([with]). */
    sealed class Change {
        /** The switch. */
        data class On(val on: Boolean) : Change()

        /** [level]'s group held back whole or let through whole: its characters' exceptions cleared. */
        data class Group(val level: Level, val held: Boolean) : Change()

        /** [codePoint] held back or let through, apart from its group where that differs. */
        data class One(val codePoint: Int, val held: Boolean) : Change()

        /** 恢复默认: [Level.EXT] alone, no exceptions, nor a group or line this build does not know; the switch as it is. */
        data object Reset : Change()
    }

    /**
     * This choice with [changes] laid over it in turn, [levels] telling each character's group;
     * the exceptions that say what their group says dropped (a group a character moved to since).
     * @throws IllegalStateException for a later version's file, which this build does not write
     */
    fun with(changes: List<Change>, levels: CharLevels): RareChoice {
        check(!newer) { "rare characters: a later version's file, not written over" }
        var on = on
        val groups = groups.toMutableSet()
        val allow = allow.toMutableSet()
        val block = block.toMutableSet()
        var otherGroups = otherGroups
        var otherLines = otherLines
        // a level or a character not the grid's: nothing
        val grids = changes.filter {
            when (it) {
                is Change.Group -> it.level in Level.GRID
                is Change.One -> levels.of(it.codePoint) in Level.GRID
                else -> true
            }
        }
        for (change in grids) {
            when (change) {
                is Change.On -> on = change.on
                is Change.Group -> {
                    if (change.held) groups += change.level else groups -= change.level
                    allow.removeAll { levels.of(it) == change.level }
                    block.removeAll { levels.of(it) == change.level }
                }
                // one its group agrees with is dropped below, the groups as they end
                is Change.One -> {
                    allow -= change.codePoint
                    block -= change.codePoint
                    (if (change.held) block else allow) += change.codePoint
                }
                Change.Reset -> {
                    groups.clear()
                    groups += DEFAULT
                    allow.clear()
                    block.clear()
                    otherGroups = emptyList()
                    otherLines = emptyList()
                }
            }
        }
        // an exception its group now agrees with, or of a character in no group of the grid's, says nothing
        allow.removeAll { cp -> levels.of(cp).let { it !in Level.GRID || it !in groups } }
        block.removeAll { cp -> levels.of(cp).let { it !in Level.GRID || it in groups } }
        return RareChoice(on, groups, allow, block, otherGroups, otherLines)
    }

    /** The file's text: [otherGroups] after the known ones, [otherLines] after the known lines, as read. */
    fun format(): String = buildString {
        appendLine(HEADER_PREFIX + VERSION)
        appendLine("$ON\t${if (on) "1" else "0"}")
        appendLine("$GROUPS\t" + (Level.GRID.filter { it in groups }.map { it.name } + otherGroups).joinToString(" "))
        appendLine("$ALLOW\t" + text(allow))
        appendLine("$BLOCK\t" + text(block))
        otherLines.forEach { appendLine(it) }
    }

    private fun text(cps: Set<Int>) = buildString { cps.sorted().forEach(::appendCodePoint) }

    override fun equals(other: Any?) = other is RareChoice && on == other.on && groups == other.groups && allow == other.allow &&
        block == other.block && otherGroups == other.otherGroups && otherLines == other.otherLines && newer == other.newer

    override fun hashCode() = listOf(on, groups, allow, block, otherGroups, otherLines, newer).hashCode()

    override fun toString() = if (newer) "RareChoice(newer)" else format()

    companion object {
        /** The file's version; bumped only when a line already there changes meaning. */
        const val VERSION = 1
        private const val HEADER_PREFIX = "# youmo-rare-chars "
        private const val ON = "on"
        private const val GROUPS = "groups"
        private const val ALLOW = "allow"
        private const val BLOCK = "block"
        private const val PLANE = 0x10000

        /** What is held back once turned on the first time, and after 恢复默认: 扩展 B on alone (DECISIONS 2026-11-14). */
        val DEFAULT: Set<Level> = setOf(Level.EXT)

        /** No file: off, [DEFAULT] the groups once on. */
        val NONE = RareChoice(false, DEFAULT, emptySet(), emptySet())

        /** The file's [text]; empty for none. */
        fun parse(text: String): RareChoice {
            if (text.isBlank()) return NONE
            val lines = text.lines()
            val header = lines.first().trim()
            if (header.startsWith(HEADER_PREFIX)) {
                val version = header.removePrefix(HEADER_PREFIX).trim().toIntOrNull()
                if (version == null || version > VERSION) return RareChoice(false, DEFAULT, emptySet(), emptySet(), newer = true)
            }
            var on = false
            var groups: Set<Level>? = null
            val otherGroups = ArrayList<String>()
            val allow = HashSet<Int>()
            val block = HashSet<Int>()
            val other = ArrayList<String>()
            for (line in lines.drop(if (header.startsWith(HEADER_PREFIX)) 1 else 0)) {
                if (line.isBlank()) continue
                val key = line.substringBefore('\t')
                val value = line.substringAfter('\t', "").trim()
                when (key) {
                    ON -> on = value == "1"
                    GROUPS -> {
                        val named = HashSet<Level>()
                        for (name in value.split(' ').filter { it.isNotEmpty() }) {
                            val level = Level.named(name)?.takeIf { it in Level.GRID }
                            if (level != null) named += level else otherGroups += name
                        }
                        groups = named
                    }
                    ALLOW -> value.codePoints().forEach { allow += it }
                    BLOCK -> value.codePoints().forEach { block += it }
                    else -> other += line
                }
            }
            return RareChoice(on, groups ?: DEFAULT, allow, block, otherGroups, other)
        }
    }
}
