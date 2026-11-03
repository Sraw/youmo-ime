/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.KeyMarkYield.Ink
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkYield.LetterKey
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkYield.Mark
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkYield.Settled
import org.fcitx.fcitx5.android.input.keyboard.KeyMarks.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class KeyMarkYieldTest {

    // a 40 by 50 face, the room 2 in from its sides and 1 from its top and bottom
    private val room = Ink(2f, 1f, 38f, 49f)

    private fun key(glyph: Ink, vararg marks: Mark) = KeyMarkYield.key(glyph, marks.toList(), room, gap = 1f)

    private fun near(a: Ink?, b: Ink?) = a == b ||
        (a != null && b != null && listOf(a.left - b.left, a.top - b.top, a.right - b.right, a.bottom - b.bottom).all { abs(it) < 0.01f })

    private fun assertInk(message: String, expected: Ink?, actual: Ink?) = assertTrue("$message: $expected, was $actual", near(expected, actual))

    private fun assertSettled(expected: Settled, actual: Settled) {
        assertInk("glyph", expected.glyph, actual.glyph)
        assertEquals(expected.marks.size, actual.marks.size)
        expected.marks.forEachIndexed { i, m -> assertInk("mark $i", m, actual.marks[i]) }
    }

    // [ink] as [slot] sees what the top right one does: the face mirrored side for side, top for bottom
    private fun Ink.at(slot: Slot): Ink {
        var i = this
        if (slot.left) i = Ink(40f - i.right, i.top, 40f - i.left, i.bottom)
        if (slot.bottom) i = Ink(i.left, 50f - i.bottom, i.right, 50f - i.top)
        return i
    }

    @Test
    fun apartNothingMoves() {
        val glyph = Ink(14f, 18f, 26f, 32f)
        val mark = Mark(Ink(30f, 3f, 36f, 11f), Slot.TopRight)
        assertSettled(Settled(glyph, listOf(mark.ink)), key(glyph, mark))
    }

    @Test
    fun aMarkMovesTowardsItsCornerTheShorterWayInEverySlot() {
        val glyph = Ink(14f, 10f, 26f, 32f)
        for (slot in Slot.entries.filter { !it.centred }) {
            // up 2 rather than right 3
            val up = Mark(Ink(24f, 3f, 30f, 11f).at(slot), slot)
            assertSettled(Settled(glyph.at(slot), listOf(Ink(24f, 1f, 30f, 9f).at(slot))), key(glyph.at(slot), up))
            // right 1 rather than up 2 (no room up for it)
            val right = Mark(Ink(26f, 2f, 32f, 11f).at(slot), slot)
            assertSettled(Settled(glyph.at(slot), listOf(Ink(27f, 2f, 33f, 11f).at(slot))), key(glyph.at(slot), right))
        }
    }

    @Test
    fun anEdgesMiddleMarkOnlyOutwards() {
        val glyph = Ink(10f, 10f, 30f, 40f)
        // the bottom middle: down 3, never sideways
        val bottom = Mark(Ink(16f, 38f, 24f, 46f), Slot.Bottom)
        assertSettled(Settled(glyph, listOf(Ink(16f, 41f, 24f, 49f))), key(glyph, bottom))
        // the top middle, a function key's down shortcut with the bottom chosen: up
        val top = Mark(Ink(16f, 4f, 24f, 12f), Slot.Top)
        assertSettled(Settled(glyph, listOf(Ink(16f, 1f, 24f, 9f))), key(glyph, top))
        // under the letter: down as far as it goes, then the letter up, before the symbol is smaller
        val below = Mark(Ink(14f, 36f, 26f, 48f), Slot.Below, minScale = 0.5f)
        assertSettled(Settled(Ink(10f, 6f, 30f, 36f), listOf(Ink(14f, 37f, 26f, 49f))), key(glyph, below))
    }

    @Test
    fun neitherAloneTheMarkIntoItsCornerThenTheGlyphMoves() {
        val glyph = Ink(10f, 6f, 30f, 40f)
        val mark = Mark(Ink(26f, 2f, 36f, 12f), Slot.TopRight, minScale = 0.5f)
        // the mark as far as it goes, its size kept; the glyph left 3, clear of it
        assertSettled(Settled(Ink(7f, 6f, 27f, 40f), listOf(Ink(28f, 1f, 38f, 11f))), key(glyph, mark))
    }

    @Test
    fun theMarkSmallerBeforeTheGlyph() {
        // a glyph as wide as the room: no move clears it, the mark smaller to its least does with the glyph down
        val glyph = Ink(2f, 4f, 38f, 46f)
        val mark = Mark(Ink(30f, 2f, 36f, 10f), Slot.TopRight, minScale = 0.6f)
        val s = key(glyph, mark)
        val m = s.marks[0]!!
        assertEquals("the glyph its size", glyph.width, s.glyph.width, 0.01f)
        assertEquals(0.6f, m.width / mark.ink.width, 0.01f)
        assertFalse(m.meets(s.glyph, 0f))
    }

    @Test
    fun theGlyphAFifthSmallerAtMostThenTheMarkLeftOut() {
        // the room's every inch the glyph's: smaller about its middle as little as clears
        val glyph = Ink(2f, 1f, 38f, 49f)
        val icon = Mark(Ink(31f, 2f, 37f, 8f), Slot.TopRight, minScale = 1f)
        val s = key(glyph, icon)
        assertTrue(s.glyph.width < glyph.width)
        assertTrue(s.glyph.width >= glyph.width * (1 - KeyMarkYield.MAX_LABEL_SHRINK) - 0.01f)
        s.marks[0]?.let { assertFalse(it.meets(s.glyph, 0f)) }
        // a mark no fifth clears: left out, the glyph a fifth smaller
        val big = Mark(Ink(14f, 2f, 26f, 24f), Slot.Top)
        val t = key(glyph, big)
        assertNull(t.marks[0])
        assertEquals(glyph.width * 0.8f, t.glyph.width, 0.01f)
    }

    @Test
    fun marksMeetingEachOtherTheMostExpendableLeftOut() {
        val glyph = Ink(16f, 30f, 24f, 40f)
        val symbol = Mark(Ink(28f, 2f, 36f, 10f), Slot.TopRight, drop = KeyMarkYield.DROP_SYMBOL)
        val shortcut = Mark(Ink(30f, 4f, 38f, 12f), Slot.BottomRight, drop = KeyMarkYield.DROP_SHORTCUT)
        assertSettled(Settled(glyph, listOf(symbol.ink, null)), key(glyph, symbol, shortcut))
        // a function key's two: its down one left out
        val up = Mark(Ink(4f, 2f, 12f, 10f), Slot.TopLeft, drop = KeyMarkYield.DROP_SHORTCUT)
        val down = Mark(Ink(6f, 8f, 14f, 16f), Slot.BottomLeft, drop = KeyMarkYield.DROP_DOWN_SHORTCUT)
        assertSettled(Settled(glyph, listOf(up.ink, null)), key(glyph, up, down))
    }

    // a big glyph filling the face but its top: a shortcut with no room in its bottom corner tried in the key's
    // other free corners, in turn, before it is left out; the glyph then as it was
    @Test
    fun aShortcutWithNoRoomTriedElsewhereBeforeLeftOut() {
        val glyph = Ink(3f, 12f, 37f, 49f)
        val br = Ink(24f, 20f, 36f, 46f)
        val tl = Ink(4f, 2f, 12f, 10f)
        val elsewhere = listOf(Slot.BottomLeft to br.at(Slot.BottomLeft).at(Slot.BottomRight), Slot.TopLeft to tl)
        val shortcut = Mark(br, Slot.BottomRight, minScale = 0.8f, elsewhere = elsewhere)
        assertSettled(Settled(glyph, listOf(tl)), key(glyph, shortcut))
        // none free: left out, as before
        assertNull(key(glyph, shortcut.copy(elsewhere = elsewhere.take(1))).marks[0])
        // a function key's two moved together, its down one mirrored: from the edges' middles to the top left and under it
        val wide = Ink(10f, 4f, 30f, 46f)
        val corner = Ink(3f, 2f, 8f, 7f)
        val up = Mark(Ink(16f, 40f, 24f, 48f), Slot.Bottom, elsewhere = listOf(Slot.TopLeft to corner, Slot.TopRight to corner.at(Slot.TopRight)))
        val down = Mark(Ink(16f, 2f, 24f, 10f), Slot.Top, drop = KeyMarkYield.DROP_DOWN_SHORTCUT,
            elsewhere = listOf(Slot.BottomLeft to corner.at(Slot.BottomLeft), Slot.BottomRight to corner.at(Slot.BottomRight)))
        val both = key(wide, up, down)
        assertNothingMeets("function", both)
        assertSettled(Settled(wide, listOf(corner, corner.at(Slot.BottomLeft))), both)
    }

    // a narrow key's two top corners: both smaller about their corners, as much, rather than one left out
    @Test
    fun marksMeetingEachOtherSmallerFirst() {
        val glyph = Ink(6f, 30f, 14f, 40f)
        val shortcut = Mark(Ink(4f, 2f, 12f, 10f), Slot.TopLeft, minScale = 0.7f)
        val symbol = Mark(Ink(10f, 2f, 16f, 8f), Slot.TopRight, minScale = 0.5f, drop = KeyMarkYield.DROP_SYMBOL)
        val s = key(glyph, shortcut, symbol)
        val (l, r) = s.marks.map { it!! }
        assertEquals(4f, l.left, 0.001f)
        assertEquals(2f, l.top, 0.001f)
        assertEquals(16f, r.right, 0.001f)
        assertEquals(2f, r.top, 0.001f)
        assertEquals("as much", l.width / 8f, r.width / 6f, 0.001f)
        assertTrue("the least that parts them: ${l.width / 8f}", l.width / 8f in 0.84f..0.86f)
        assertTrue(!l.meets(r, 0f))
        assertEquals(glyph, s.glyph)
    }

    @Test
    fun minScaleOfASize() {
        assertEquals(0.6f, KeyMarkYield.minScale(KeyMarkYield.MIN_SYMBOL_DP, 10f), 0.001f)
        assertEquals(1f, KeyMarkYield.minScale(KeyMarkYield.MIN_ICON_DP, 6f), 0.001f)
        assertEquals(1f, KeyMarkYield.minScale(KeyMarkYield.MIN_ICON_DP, 0f), 0.001f)
    }

    // ---- letters

    private fun letter(ink: Ink, vararg marks: Mark) = LetterKey(ink, baseline = ink.bottom, marks.toList(), room)

    @Test
    fun lettersApartNothingMoves() {
        val a = letter(Ink(10f, 14f, 30f, 40f), Mark(Ink(30f, 2f, 36f, 10f), Slot.TopRight))
        val l = KeyMarkYield.letters(listOf(a), gap = 1f)
        assertEquals(1f, l.scale)
        assertSettled(Settled(a.letter, listOf(a.marks[0].ink)), l.keys[0])
    }

    @Test
    fun everyLetterAsMuchSmallerAsTheOneThatNeedsItMost() {
        val ink = Ink(10f, 10f, 30f, 40f)
        // an icon as small as it goes, as far in: the letter 0.9 of itself clears it, and the other key's too
        val a = letter(ink, Mark(Ink(28f, 2f, 36f, 14f), Slot.TopRight))
        val b = letter(ink)
        val l = KeyMarkYield.letters(listOf(a, b), gap = 1f)
        assertEquals(0.9f, l.scale, 0.001f)
        assertSettled(Settled(Ink(11f, 13f, 29f, 40f), listOf(Ink(30f, 2f, 38f, 14f))), l.keys[0])
        assertSettled(Settled(Ink(11f, 13f, 29f, 40f), emptyList()), l.keys[1])
    }

    @Test
    fun aMarkNeedingMoreThanAQuarterLeftOutTheLettersKept() {
        val ink = Ink(4f, 4f, 36f, 46f)
        val a = letter(ink, Mark(Ink(14f, 2f, 26f, 30f), Slot.Top))
        val l = KeyMarkYield.letters(listOf(a), gap = 1f)
        assertEquals(1f, l.scale)
        assertSettled(Settled(ink, listOf(null)), l.keys[0])
    }

    // ---- whatever is drawn never meets anything else drawn on its key

    private fun Random.ink(x0: Float, y0: Float, x1: Float, y1: Float, w: Float, h: Float): Ink {
        val x = x0 + nextFloat() * (x1 - x0 - w)
        val y = y0 + nextFloat() * (y1 - y0 - h)
        return Ink(x, y, x + w, y + h)
    }

    // a mark of about [w] by [h] near [slot] of the face
    private fun Random.markAt(slot: Slot, drop: Int): Mark {
        val w = 4f + nextFloat() * 10f
        val h = 4f + nextFloat() * 8f
        val top = Ink(2f + nextFloat() * 4f, 1f + nextFloat() * 6f, 0f, 0f)
        val x = when {
            slot.left -> top.left
            slot.right -> 38f - top.left - w
            else -> 20f - w / 2 + (nextFloat() - 0.5f) * 2f
        }
        val y = if (slot.bottom) 49f - top.top - h else top.top
        return Mark(Ink(x, y, x + w, y + h), slot, minScale = 0.5f + nextFloat() * 0.5f, drop = drop)
    }

    // [m] as big in one to three other corners
    private fun Random.corners(m: Mark): List<Pair<Slot, Ink>> =
        listOf(Slot.TopLeft, Slot.TopRight, Slot.BottomLeft, Slot.BottomRight).filter { it != m.slot }.shuffled(this).take(1 + nextInt(3))
            .map { it to markAt(it, m.drop).ink.let { i -> Ink(i.left, i.top, i.left + m.ink.width, i.top + m.ink.height) } }

    private fun assertNothingMeets(case: String, s: Settled) {
        val drawn = listOf(s.glyph) + s.marks.filterNotNull()
        for (i in drawn.indices) for (j in drawn.indices) if (i < j) assertFalse("$case: $i ${drawn[i]} meets $j ${drawn[j]}", drawn[i].meets(drawn[j], 0f))
    }

    @Test
    fun noKeyEverDrawsTwoThingsOverEachOther() {
        val r = Random(51)
        val slots = Slot.entries
        repeat(4000) { n ->
            val glyph = r.ink(4f, 4f, 36f, 46f, 8f + r.nextFloat() * 26f, 10f + r.nextFloat() * 32f)
            val marks = slots.shuffled(r).take(1 + r.nextInt(3)).mapIndexed { i, slot -> r.markAt(slot, KeyMarkYield.DROP_SYMBOL + i) }
                .map { m -> if (m.drop == KeyMarkYield.DROP_SYMBOL || r.nextBoolean()) m else m.copy(elsewhere = r.corners(m)) }
            val s = key(glyph, *marks.toTypedArray())
            assertNothingMeets("key $n $glyph $marks", s)
            assertTrue("glyph $n", s.glyph.width >= glyph.width * (1 - KeyMarkYield.MAX_LABEL_SHRINK) - 0.01f)
            marks.forEachIndexed { i, m -> s.marks[i]?.let { assertTrue("mark $n $i", it.width >= m.ink.width * m.minScale - 0.01f) } }
        }
        repeat(500) { n ->
            val keys = List(1 + r.nextInt(6)) {
                val ink = r.ink(4f, 4f, 36f, 46f, 10f + r.nextFloat() * 20f, 14f + r.nextFloat() * 24f)
                val marks = slots.shuffled(r).take(r.nextInt(3)).mapIndexed { i, slot -> r.markAt(slot, i + 1) }
                    .map { m -> if (m.drop == KeyMarkYield.DROP_SYMBOL || r.nextBoolean()) m else m.copy(elsewhere = r.corners(m)) }
                LetterKey(ink, ink.bottom - r.nextFloat() * 4f, marks, room)
            }
            val l = KeyMarkYield.letters(keys, gap = 1f)
            assertTrue(l.scale >= 1 - KeyMarkYield.MAX_LETTER_SHRINK - 0.001f)
            l.keys.forEachIndexed { k, s -> assertNothingMeets("letters $n key $k ${keys[k]}", s) }
        }
    }
}
