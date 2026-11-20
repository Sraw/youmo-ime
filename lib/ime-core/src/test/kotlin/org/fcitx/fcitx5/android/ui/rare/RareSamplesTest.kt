/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.rare

import org.fcitx.fcitx5.android.engine.data.CharLevels
import org.fcitx.fcitx5.android.engine.data.CharLevels.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What holds without the APK's table and fonts (those are RareSamplesGlyphTest's). */
class RareSamplesTest {

    private val all: (String) -> Boolean = { true }

    @Test
    fun eachGroupHasSixPickedOnesEachOneCharacterNoneTwice() {
        assertEquals(Level.GRID.toSet(), RareSamples.PICKED.keys)
        val every = RareSamples.PICKED.values.flatten()
        RareSamples.PICKED.forEach { (level, picked) ->
            assertEquals("$level", RareSamples.COUNT, picked.size)
            picked.forEach { assertEquals("$level $it", 1, it.codePointCount(0, it.length)) }
        }
        assertEquals(every.size, every.toSet().size)
    }

    // the groups the table can't be asked here are the ranges': 扩展区 planes 2 and 3, the rest the basic block or 扩展 A
    @Test
    fun thePickedAreInTheirGroupsRanges() {
        val ext = RareSamples.PICKED.getValue(Level.EXT).map { it.codePointAt(0) }
        assertTrue(ext.joinToString { "U+%X".format(it) }, ext.all { it in CharLevels.EXT_RANGE })
        listOf(Level.L3, Level.TRAD, Level.OUT).forEach { level ->
            RareSamples.PICKED.getValue(level).forEach {
                assertEquals("$level $it", Level.OUT, CharLevels.rangeOf(it.codePointAt(0)))
            }
        }
    }

    @Test
    fun thePickedComeFirstAsTheyAreAll() =
        assertEquals(RareSamples.PICKED.getValue(Level.L3), RareSamples.of(Level.L3, listOf("a", "b"), all))

    @Test
    fun aPickedOneNotDrawnIsLeftOutAndTheRestTakenEvenlyFromTheGroup() {
        val notDrawn = setOf("堃", "淼")
        val shown = (0 until 100).map { "x$it" }
        val got = RareSamples.of(Level.L3, shown, { it !in notDrawn })
        assertEquals(listOf("犇", "喆", "垚", "芃", "x0", "x50"), got)
    }

    @Test
    fun aPickedOneAlsoInTheGroupIsNotTakenTwice() {
        val got = RareSamples.of(Level.OUT, listOf("龘", "龘", "y"), { it != "囧" })
        assertEquals(listOf("龘", "槑", "嫑", "夲", "烎", "y"), got)
    }

    @Test
    fun noPickedOneDrawnSixSpreadOverTheGroup() {
        val shown = (0 until 12).map { "c$it" }
        assertEquals(listOf("c0", "c2", "c4", "c6", "c8", "c10"), RareSamples.of(Level.EXT, shown, { it.startsWith("c") }))
    }

    @Test
    fun aGroupOfFewerThanNeededGivesWhatItHas() =
        assertEquals(listOf("p", "q"), RareSamples.of(Level.TRAD, listOf("p", "q"), { it.length == 1 && it[0].code < 0x80 }))

    @Test
    fun nothingDrawnNothingShown() =
        assertEquals(emptyList<String>(), RareSamples.of(Level.EXT, emptyList(), { false }))

    @Test
    fun aFewAskedFor() {
        assertEquals(listOf("愛", "國"), RareSamples.of(Level.TRAD, emptyList(), all, n = 2))
        assertThrows(IllegalArgumentException::class.java) { RareSamples.of(Level.TRAD, emptyList(), all, n = -1) }
    }
}
