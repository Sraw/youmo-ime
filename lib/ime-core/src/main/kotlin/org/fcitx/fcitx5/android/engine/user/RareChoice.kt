/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.CharLevels
import org.fcitx.fcitx5.android.engine.data.CharLevels.Level
import java.util.BitSet

/**
 * The user's 生僻字屏蔽 choice: [on], the [groups] held whole, and per-character [allow]/[block] exceptions. A file
 * of its own (RareCharsManager):
 *
 * ```
 * # youmo-rare-chars 1
 * on	1
 * groups	EXT
 * allow	𠀀𤝰
 * block	犇
 * ```
 *
 * No `on 1` line is off; no `groups` line is [Level.EXT] alone. Unknown groups and lines are kept and written
 * back, so adding either needs no new version; a [newer] file is off and never overwritten. L1, L2 and
 * ungrouped characters are never held back.
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
    fun holdsGroup(level: Level): Boolean = level in Level.GRID && level in groups

    /** Whether [codePoint] in [level] is held back: never L1, L2 or ungrouped; else [block] holds, [allow] lets through (both: held), else its group. */
    fun holds(codePoint: Int, level: Level?): Boolean = when {
        level == null || level !in Level.GRID -> false
        codePoint in block -> true
        codePoint in allow -> false
        else -> level in groups
    }

    /** The gate of this choice: none while off, for a newer file, or without [levels]. */
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

    sealed class Change {
        data class On(val on: Boolean) : Change()

        data class Group(val level: Level, val held: Boolean) : Change()

        data class One(val codePoint: Int, val held: Boolean) : Change()

        /** 恢复默认: [Level.EXT] alone, no exceptions, no unknown groups or lines; the switch as it is. */
        data object Reset : Change()
    }

    /**
     * This choice with [changes] applied in turn; exceptions that now agree with their group are dropped.
     * @throws IllegalStateException for a newer file, which this build does not write
     */
    fun with(changes: List<Change>, levels: CharLevels): RareChoice {
        check(!newer) { "rare characters: a later version's file, not written over" }
        var on = on
        val groups = groups.toMutableSet()
        val allow = allow.toMutableSet()
        val block = block.toMutableSet()
        var otherGroups = otherGroups
        var otherLines = otherLines
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
        allow.removeAll { cp -> levels.of(cp).let { it !in Level.GRID || it !in groups } }
        block.removeAll { cp -> levels.of(cp).let { it !in Level.GRID || it in groups } }
        return RareChoice(on, groups, allow, block, otherGroups, otherLines)
    }

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
        /** Bumped only when a line already there changes meaning. */
        const val VERSION = 1
        private const val HEADER_PREFIX = "# youmo-rare-chars "
        private const val ON = "on"
        private const val GROUPS = "groups"
        private const val ALLOW = "allow"
        private const val BLOCK = "block"
        private const val PLANE = 0x10000

        /** What is held back once turned on the first time, and after 恢复默认: 扩展 B on alone. */
        val DEFAULT: Set<Level> = setOf(Level.EXT)

        val NONE = RareChoice(false, DEFAULT, emptySet(), emptySet())

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
