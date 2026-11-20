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

/** The characters 生僻字屏蔽's grid shows, with readings, copied from the pinyin data into plain arrays on the fcitx thread ([copy], a few hundred KB), so the page holds no mapped table the engine may drop. [unread]: per group, characters with no known reading (no cell). */
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

    fun readings(i: Int): List<Reading> = (readingStart[i] until readingStart[i + 1]).map { Reading(syllables[it], tones[it].toInt()) }

    fun spelling(i: Int): String = Syllables.spelling(syllables[readingStart[i]])

    fun count(level: Level): Int = levels.count { it.toInt() == level.ordinal }

    /** Each group's indexes by first reading's spelling, tone, then code point; run off the fcitx thread. */
    fun sorted(): Map<Level, IntArray> {
        val spellings = Array(size) { spelling(it) }
        val order = (0 until size).sortedWith(
            compareBy<Int>({ spellings[it] }, { tones[readingStart[it]].toInt() }, { chars[it] }),
        )
        return Level.GRID.associateWith { level -> order.filter { levels[it].toInt() == level.ordinal }.toIntArray() }
    }

    /** The cells read [spelling] in any reading (toneless, `ü` as `v`). */
    fun find(spelling: String, sorted: Map<Level, IntArray>): List<Int> {
        val wanted = spelling.lowercase().replace('ü', 'v')
        return Level.GRID.flatMap { level ->
            (sorted[level] ?: IntArray(0)).filter { i -> (readingStart[i] until readingStart[i + 1]).any { Syllables.spelling(syllables[it]) == wanted } }
        }
    }

    fun indexOf(codePoint: Int): Int = chars.indexOf(codePoint)

    companion object {
        val EMPTY = RareGrid(IntArray(0), ByteArray(0), IntArray(1), IntArray(0), ByteArray(0), IntArray(Level.GRID.size))

        /** The grid of [levels] over [readings]; of [Level.EXT] only what something [typed] (~2,600 others would be cells for nothing). [others] counts per group what no reading knows. */
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
