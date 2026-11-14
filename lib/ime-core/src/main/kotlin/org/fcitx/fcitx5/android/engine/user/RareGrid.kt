/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.CharLevels
import org.fcitx.fcitx5.android.engine.data.CharLevels.Level
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.reading.CharReadings
import org.fcitx.fcitx5.android.engine.reading.Reading

/**
 * The characters 生僻字屏蔽's grid shows (dev/design/061-rare-char-block.md §2.3, §6): those of the
 * grid's groups the readings know, each with its readings, copied out of the pinyin data as plain
 * arrays on the fcitx thread ([copy], a few hundred KB, no object a character), so the page holds
 * no mapped table the engine may let go of; sorted for the page off it ([sorted]). [unread], by
 * group ([Level.GRID]'s order), how many characters the dictionary or the strokes have that no
 * reading is known of: no cell, held back or let through with their group.
 */
class RareGrid private constructor(
    val chars: IntArray,
    private val levels: ByteArray,
    private val readingStart: IntArray,
    private val syllables: IntArray,
    private val tones: ByteArray,
    val unread: IntArray,
) {
    val size: Int get() = chars.size

    fun level(i: Int): Level = Level.entries[levels[i].toInt()]

    /** The [i]th character's readings, the commonest first. */
    fun readings(i: Int): List<Reading> = (readingStart[i] until readingStart[i + 1]).map { Reading(syllables[it], tones[it].toInt()) }

    /** The [i]th character's first reading, toneless, as it is searched by ([find]). */
    fun spelling(i: Int): String = Syllables.spelling(syllables[readingStart[i]])

    /** How many of [level]'s characters have a cell. */
    fun count(level: Level): Int = levels.count { it.toInt() == level.ordinal }

    /**
     * The grid's groups, each its characters' indexes in the grid's order: by the first reading's
     * spelling (a–z), then its tone, then the code point (§6.3). Run off the fcitx thread.
     */
    fun sorted(): Map<Level, IntArray> {
        val spellings = Array(size) { spelling(it) }
        val order = (0 until size).sortedWith(
            compareBy<Int>({ spellings[it] }, { tones[readingStart[it]].toInt() }, { chars[it] }),
        )
        return Level.GRID.associateWith { level -> order.filter { levels[it].toInt() == level.ordinal }.toIntArray() }
    }

    /** The cells read [spelling] in any reading (toneless, `ü` as `v`), each group's in [sorted]'s order. */
    fun find(spelling: String, sorted: Map<Level, IntArray>): List<Int> {
        val wanted = spelling.lowercase().replace('ü', 'v')
        return Level.GRID.flatMap { level ->
            (sorted[level] ?: IntArray(0)).filter { i -> (readingStart[i] until readingStart[i + 1]).any { Syllables.spelling(syllables[it]) == wanted } }
        }
    }

    /** The cell of [codePoint]; -1 for none. */
    fun indexOf(codePoint: Int): Int = chars.indexOf(codePoint)

    companion object {
        val EMPTY = RareGrid(IntArray(0), ByteArray(0), IntArray(1), IntArray(0), ByteArray(0), IntArray(Level.GRID.size))

        /**
         * The grid of [levels] over what [readings] knows, of [Level.EXT] only what something [typed]
         * (DECISIONS N2: the readings know ~2,600 of it nothing offers, a cell to hold for nothing);
         * [others], the code points the dictionary and the strokes have (repeats allowed), counted by
         * group where no reading is known ([unread]).
         */
        fun copy(levels: CharLevels, readings: CharReadings, others: Sequence<Int>, typed: (Int) -> Boolean = { true }): RareGrid {
            val chars = ArrayList<Int>()
            val lv = ArrayList<Byte>()
            val start = ArrayList<Int>().apply { add(0) }
            val syl = ArrayList<Int>()
            val tone = ArrayList<Byte>()
            for (k in 0 until readings.characters) {
                val cp = readings.codePointAt(k)
                val level = levels.of(cp)?.takeIf { it in Level.GRID }
                val rs = readings.of(cp)
                val untyped = level == Level.EXT && !typed(cp)
                if (level == null || rs.isEmpty() || untyped) continue
                chars += cp
                lv += level.ordinal.toByte()
                rs.forEach {
                    syl += it.syllable
                    tone += it.tone.toByte()
                }
                start += syl.size
            }
            val known = chars.toHashSet()
            val unread = IntArray(Level.GRID.size)
            val counted = HashSet<Int>()
            for (cp in others) {
                if (cp in known || !counted.add(cp)) continue
                val g = Level.GRID.indexOf(levels.of(cp))
                if (g >= 0) unread[g]++
            }
            return RareGrid(chars.toIntArray(), lv.toByteArray(), start.toIntArray(), syl.toIntArray(), tone.toByteArray(), unread)
        }
    }
}
