/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.rules.KeyMarkYield
import org.fcitx.fcitx5.android.scene.rules.KeyMarkYield.Ink
import org.fcitx.fcitx5.android.scene.rules.KeyMarkYield.Mark
import org.fcitx.fcitx5.android.scene.rules.KeyMarks.Slot
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The yield's last step before a mark is left out: the marks, at their least, eat into their insets
 * (rules B §7). A key here is a face [w] × [h] px at [d] px a dp, the yield's room 2 dp and 1 dp in,
 * the marks' edge a quarter dp in, a mark an 8 dp icon (7 dp at least) 2 dp and 1 dp in.
 */
class KeyMarkYieldTest {

    private class Key(val w: Double, val h: Double, val d: Double) {
        val face = Ink(0.0, 0.0, w, h)
        val room = Ink(2 * d, 1 * d, w - 2 * d, h - 1 * d)
        val edge = Ink(0.25 * d, 0.25 * d, w - 0.25 * d, h - 0.25 * d)
        val size = 8 * d
        val least = 7.0 / 8.0

        fun mark(slot: Slot, drop: Int = KeyMarkYield.DROP_SHORTCUT, elsewhere: List<Slot> = emptyList()): Mark =
            Mark(at(slot), slot, least, drop, elsewhere.map { it to at(it) })

        fun at(slot: Slot): Ink {
            val x = if (slot.left) 2 * d else w - 2 * d - size
            val y = if (slot.bottom) h - 1 * d - size else 1 * d
            return Ink(x, y, x + size, y + size)
        }

        // a glyph [gw] × [gh] px in the middle
        fun glyph(gw: Double, gh: Double) = Ink((w - gw) / 2, (h - gh) / 2, (w + gw) / 2, (h + gh) / 2)
    }

    private fun near(e: Double, a: Double, what: String) = assertTrue(abs(e - a) < 0.02, "$what: expected $e, was $a")

    private fun inside(i: Ink, o: Ink) = i.left >= o.left - 1e-6 && i.top >= o.top - 1e-6 && i.right <= o.right + 1e-6 && i.bottom <= o.bottom + 1e-6

    @Test
    fun twoMarksHalfAPxTooTallAtTheirLeastEatAQuarterPxOfTheirInsetsEach() {
        // the small phone's ⌫: 43.5 px tall at 2.75, two 22 px icons in a room 38 tall; at their least 38.5
        val k = Key(148.0, 43.5, 2.75)
        val glyph = Ink(60.0, 10.0, 88.0, 33.5)
        val s = KeyMarkYield.key(glyph, listOf(k.mark(Slot.TopLeft), k.mark(Slot.BottomLeft, KeyMarkYield.DROP_DOWN_SHORTCUT)), k.room, 2.75, k.edge)
        val up = assertNotNull(s.marks[0], "up kept")
        val down = assertNotNull(s.marks[1], "down kept")
        // smaller first, to their least, then out the least that parts them: a quarter px each
        near(19.25, up.height, "up at its least")
        near(19.25, down.height, "down at its least")
        near(k.room.top - 0.25, up.top, "up out a quarter px")
        near(k.room.bottom + 0.25, down.bottom, "down out a quarter px")
        near(k.room.left, up.left, "up not sideways")
        near(k.room.left, down.left, "down not sideways")
        assertTrue(up.bottom <= down.top + 1e-6, "apart: $up $down")
        assertEquals(glyph, s.glyph, "the glyph as it was")
        // with no edge, as before: one left out
        val before = KeyMarkYield.key(glyph, listOf(k.mark(Slot.TopLeft), k.mark(Slot.BottomLeft, KeyMarkYield.DROP_DOWN_SHORTCUT)), k.room, 2.75)
        assertEquals(null, before.marks[1], "no edge: the down one left out")
    }

    @Test
    fun whateverTheDensityAndKeyNothingIsLeftOutThatFitsItsInsetEaten() {
        var eaten = 0
        var left = 0
        val grid = listOf(2.0, 2.625, 2.75, 3.0, 3.5).flatMap { d ->
            listOf(24.0, 30.0, 36.0, 48.0, 60.0).flatMap { w -> listOf(14.0, 14.5, 14.75, 15.0, 15.5, 16.0, 20.0, 40.0).map { Triple(d, w, it) } }
        }
        for ((d, wDp, hDp) in grid) {
            val k = Key(wDp * d, hDp * d, d)
            val at = "at $d, a face $wDp × $hDp dp"
            // two marks one over the other, a small glyph clear of them
            val glyph = k.glyph(4 * d, 4 * d)
            val marks = listOf(k.mark(Slot.TopLeft), k.mark(Slot.BottomLeft, KeyMarkYield.DROP_DOWN_SHORTCUT))
            val s = KeyMarkYield.key(glyph, marks, k.room, 1.0 * d, k.edge)
            val fits = k.edge.height >= 2 * k.least * k.size - 1e-6
            if (fits) {
                assertTrue(s.marks.all { it != null }, "both kept $at: ${s.marks}")
                assertTrue(s.marks.all { inside(it!!, k.edge) }, "within the edge $at: ${s.marks}")
                assertTrue(!s.marks[0]!!.meets(s.marks[1]!!, 0.0), "apart $at: ${s.marks}")
                if (k.room.height < 2 * k.least * k.size - 1e-6) eaten++
            } else {
                left++
                assertEquals(null, s.marks[1], "the down one left out $at")
            }
            // what the room alone held, the edge leaves as it was
            val roomOnly = KeyMarkYield.key(glyph, marks, k.room, 1.0 * d)
            if (roomOnly.marks.all { it != null }) assertEquals(roomOnly, s, "as it was $at")
        }
        assertTrue(eaten >= 10 && left >= 10, "eaten $eaten, left out $left")
    }

    @Test
    fun aMarkMeetingTheGlyphEatsItsInsetBeforeTheGlyphIsSmallerOrTheMarkTriedInAnotherCornerOrLeftOut() {
        var rescued = 0
        var spared = 0
        for (d in listOf(2.0, 2.625, 2.75, 3.0, 3.5)) {
            for (wDp in listOf(20.0, 24.0, 28.0, 32.0, 40.0)) {
                val k = Key(wDp * d, 40 * d, d)
                // a glyph too tall for the mark to clear it upwards even a fifth smaller, wider and wider: the
                // mark sideways, then nowhere in the room but out into its inset, then nowhere at all
                var gw = 4 * d
                while (gw <= k.room.width) {
                    val at = "at $d, a face $wDp dp wide, a glyph $gw px"
                    val glyph = k.glyph(gw, k.h * 1.25)
                    val mark = k.mark(Slot.TopRight, elsewhere = listOf(Slot.TopLeft))
                    val s = KeyMarkYield.key(glyph, listOf(mark), k.room, 1.0 * d, k.edge)
                    val before = KeyMarkYield.key(glyph, listOf(mark), k.room, 1.0 * d)
                    val m = s.marks[0]
                    // in its own corner, the right
                    fun mine(i: Ink) = i.left + i.right > k.w
                    val own = m != null && mine(m)
                    if (m != null) {
                        assertTrue(inside(m, k.edge), "within the edge $at: $m")
                        assertTrue(!m.meets(s.glyph, 1.0 * d - 1e-6), "clear of the glyph $at: $m ${s.glyph}")
                    }
                    val b = before.marks[0]
                    // what the room alone held in its own corner, the glyph its size, the edge leaves as it was
                    if (b != null && mine(b) && before.glyph.width == glyph.width) {
                        assertEquals(before, s, "as it was $at")
                    }
                    // the glyph never smaller for the edge; as large as it was where the mark eats its inset instead
                    assertTrue(s.glyph.width >= before.glyph.width - 1e-6, "the glyph no smaller $at: $s $before")
                    if (own && s.glyph.width > before.glyph.width + 1e-6) {
                        spared++
                        assertTrue(!inside(m!!, k.room), "out into its inset, the glyph spared $at: $m")
                    }
                    // eaten into its inset in its own corner rather than elsewhere or left out
                    if (own && (b == null || !mine(b))) {
                        rescued++
                        assertTrue(!inside(m!!, k.room), "out into its inset $at: $m")
                    }
                    // left out or elsewhere with the edge: so too with no edge
                    if (!own) assertTrue(b == null || !mine(b), "no edge kept it where the edge did not $at: $s $before")
                    gw += 0.25 * d
                }
            }
        }
        assertTrue(rescued >= 10 && spared >= 10, "kept in its corner by its inset: $rescued, the glyph spared: $spared")
    }

    @Test
    fun aMarkEatsItsInsetRatherThanTheGlyphIsSmaller() {
        for (d in listOf(2.0, 2.625, 2.75, 3.0, 3.5)) {
            val k = Key(30 * d, 40 * d, d)
            // a glyph too tall to pass over, as wide as the room less the icon at its least (7 dp) and the gap but
            // half a dp: the icon out into its side inset, the glyph moved, its size kept
            val gw = k.room.width - 7 * d - 1.0 * d + 0.5 * d
            val glyph = k.glyph(gw, k.h * 1.25)
            val s = KeyMarkYield.key(glyph, listOf(k.mark(Slot.TopRight)), k.room, 1.0 * d, k.edge)
            val at = "at $d"
            near(gw, s.glyph.width, "the glyph its size $at")
            val m = assertNotNull(s.marks[0], "kept $at")
            assertTrue(m.right > k.room.right && inside(m, k.edge), "out into its inset $at: $m")
            assertTrue(KeyMarkYield.key(glyph, listOf(k.mark(Slot.TopRight)), k.room, 1.0 * d).glyph.width < gw, "no edge: the glyph smaller $at")
        }
    }

    @Test
    fun aLettersIconEatsItsInsetBeforeEveryLetterIsSmaller() {
        for (d in listOf(2.0, 2.625, 2.75, 3.0, 3.5)) {
            val k = Key(30 * d, 50 * d, d)
            val gap = 1.0 * d
            // a letter as wide as the room, its top under the top left icon at its least by [over] dp: within the
            // inset's three quarters of a dp, the icon goes up, the letters as they were; beyond, they are smaller,
            // but less than with no inset to eat
            for (over in listOf(0.25, 0.5, 0.74, 1.0, 1.5)) {
                val top = k.room.top + k.least * k.size + gap - over * d
                val letter = Ink(k.room.left, top, k.room.right, k.room.bottom)
                val key = KeyMarkYield.LetterKey(letter, letter.bottom, listOf(k.mark(Slot.TopLeft)), k.room, k.edge)
                val at = "at $d, $over dp under the icon"
                val eaten = KeyMarkYield.letters(listOf(key), gap)
                val before = KeyMarkYield.letters(listOf(key.copy(edge = k.room)), gap)
                assertTrue(before.scale < 1.0, "no inset to eat: smaller $at")
                val icon = assertNotNull(eaten.keys[0].marks[0], "kept $at")
                assertTrue(inside(icon, k.edge), "within the edge $at: $icon")
                if (over < 0.75) {
                    assertEquals(1.0, eaten.scale, "the letters as they were $at")
                    near(k.room.top - over * d, icon.top, "the icon up as much $at")
                } else {
                    assertTrue(eaten.scale < 1.0 && eaten.scale > before.scale, "smaller, less so $at: ${eaten.scale} ${before.scale}")
                    near(k.edge.top, icon.top, "the icon up to the edge $at")
                }
            }
        }
    }
}
