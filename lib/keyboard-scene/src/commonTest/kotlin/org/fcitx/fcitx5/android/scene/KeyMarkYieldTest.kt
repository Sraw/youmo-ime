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
import kotlin.math.sqrt
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

    private class Key(val w: Double, val h: Double, val d: Double, val radii: List<Double> = List(4) { 0.0 }) {
        constructor(w: Double, h: Double, d: Double, r: Double) : this(w, h, d, List(4) { r })

        val face = Ink(0.0, 0.0, w, h)
        val room = Ink(2 * d, 1 * d, w - 2 * d, h - 1 * d)
        val bound = Ink(0.25 * d, 0.25 * d, w - 0.25 * d, h - 0.25 * d)
        val edge = KeyMarkYield.Edge(face, radii.map { it * d }, 0.25 * d)
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
            val fits = k.bound.height >= 2 * k.least * k.size - 1e-6
            if (fits) {
                assertTrue(s.marks.all { it != null }, "both kept $at: ${s.marks}")
                assertTrue(s.marks.all { inside(it!!, k.bound) }, "within the edge $at: ${s.marks}")
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

    // [s] (with the edge) and [before] (with none), one mark each: the mark kept both ways, the glyph never
    // smaller for the edge; the mark left out, the glyph as it was (nothing left to give way to)
    private fun glyphsNoSmaller(glyph: Ink, s: KeyMarkYield.Settled, before: KeyMarkYield.Settled, at: String) {
        if (s.marks[0] != null && before.marks[0] != null) assertTrue(s.glyph.width >= before.glyph.width - 1e-6, "the glyph no smaller $at: $s $before")
        if (s.marks[0] == null) assertEquals(glyph, s.glyph, "left out, the glyph its own $at")
        if (before.marks[0] == null) assertEquals(glyph, before.glyph, "left out with no edge, the glyph its own $at")
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
                        assertTrue(inside(m, k.bound), "within the edge $at: $m")
                        assertTrue(!m.meets(s.glyph, 1.0 * d - 1e-6), "clear of the glyph $at: $m ${s.glyph}")
                    }
                    val b = before.marks[0]
                    // what the room alone held in its own corner, the glyph its size, the edge leaves as it was
                    if (b != null && mine(b) && before.glyph.width == glyph.width) {
                        assertEquals(before, s, "as it was $at")
                    }
                    // the glyph never smaller for the edge; as large as it was where the mark eats its inset instead
                    glyphsNoSmaller(glyph, s, before, at)
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
            assertTrue(m.right > k.room.right && inside(m, k.bound), "out into its inset $at: $m")
            // the glyph moved to the room's left first (3.75 dp), the icon out only the half dp still short, not
            // its whole inset (a dp and three quarters)
            near(k.room.left, s.glyph.left, "the glyph moved first $at")
            assertTrue(m.right >= k.room.right + 0.5 * d - 1e-6 && m.right < k.room.right + 0.5 * d + 0.01 * d, "out half a dp $at: $m")
            assertTrue(KeyMarkYield.key(glyph, listOf(k.mark(Slot.TopRight)), k.room, 1.0 * d).glyph.width < gw, "no edge: the glyph smaller $at")
        }
    }

    // a glyph as tall as the key and more, from the room's left (it moves no further left), [over] dp too wide
    // for a top right icon at its least: up parts nothing, sideways does
    private fun tall(k: Key, over: Double) = Ink(k.room.left, -k.h / 8, k.room.left + k.room.width - 7 * k.d - 1.0 * k.d + over * k.d, k.h * 9 / 8)

    @Test
    fun aMarkEatsOnlyTheInsetOnTheSideThatPartsIt() {
        // the glyph too wide for the icon by its side inset to the edge (a dp and three quarters; rounded 4 dp,
        // where the arc is a dp down) or less: the icon goes as far sideways as it must, its top inset kept (a
        // move up parts nothing); more, it goes to the edge and the glyph is smaller
        for (d in listOf(2.0, 2.625, 2.75, 3.0, 3.5)) for (r in listOf(0.0, 4.0)) {
            val k = Key(30 * d, 40 * d, d, r)
            // how far out its side goes, its top a dp in: a quarter dp in, or (rounded) where the arc is a dp down
            val rp = r * d
            val arc = if (rp > d) rp - sqrt(rp * rp - (rp - d) * (rp - d)) else 0.0
            val edge = k.w - maxOf(0.25 * d, arc)
            for (over in listOf(0.5, 0.6, 1.0, 1.5, 1.75, 2.0, 2.5, 3.0)) {
                val glyph = tall(k, over)
                val s = KeyMarkYield.key(glyph, listOf(k.mark(Slot.TopRight)), k.room, 1.0 * d, k.edge)
                val at = "at $d, r $r, $over dp over"
                val m = assertNotNull(s.marks[0], "kept $at")
                near(k.room.top, m.top, "its top inset kept $at")
                near(k.least * k.size, m.height, "at its least $at")
                if (k.room.right + over * d <= edge + 1e-6) {
                    near(glyph.width, s.glyph.width, "the glyph its size $at")
                    near(k.room.right + over * d, m.right, "out as far as it must $at")
                } else {
                    // the glyph a hundredth smaller at a time: the icon out to the edge but what that hundredth gives
                    assertTrue(m.right <= edge + 1e-6 && m.right >= edge - 0.01 * glyph.width, "out to the edge $at: $m")
                    assertTrue(s.glyph.width < glyph.width, "the glyph smaller $at")
                }
            }
        }
    }

    // the marks of [s] each with its outer corner within [k]'s arcs ([radii], dp: top left, top right, bottom
    // right, bottom left); how many of them are against an arc, a twentieth of a px from it
    private fun withinArcs(k: Key, radii: List<Double>, s: KeyMarkYield.Settled, at: String): Int = s.marks.filterNotNull().count { m ->
        val left = m.left + m.right < k.w
        val top = m.top + m.bottom < k.h
        val r = radii[if (top) (if (left) 0 else 1) else (if (left) 3 else 2)] * k.d
        val x = if (left) maxOf(0.0, r - m.left) else maxOf(0.0, m.right - (k.w - r))
        val y = if (top) maxOf(0.0, r - m.top) else maxOf(0.0, m.bottom - (k.h - r))
        assertTrue(sqrt(x * x + y * y) <= r + 1e-6, "within the arc $at: $m")
        x > 0 && y > 0 && sqrt(x * x + y * y) > r - 0.05
    }

    // a key's marks out into their insets every way the yield takes them: one meeting a glyph (its four
    // corners in turn), two meeting each other one over the other
    private fun yields(k: Key): List<KeyMarkYield.Settled> {
        val d = k.d
        val corners = listOf(Slot.TopRight, Slot.TopLeft, Slot.BottomRight, Slot.BottomLeft)
        return listOf(0.5, 1.0, 2.0, 3.0).flatMap { over ->
            val glyph = k.glyph(k.room.width - 7 * d - 1.0 * d + over * d, k.h - 2 * d - 7 * d + over * d)
            corners.map { KeyMarkYield.key(glyph, listOf(k.mark(it)), k.room, 1.0 * d, k.edge) }
        } + listOf(Slot.TopLeft to Slot.BottomLeft, Slot.TopRight to Slot.BottomRight).map { (a, b) ->
            KeyMarkYield.key(k.glyph(2.0, 2.0), listOf(k.mark(a), k.mark(b, KeyMarkYield.DROP_DOWN_SHORTCUT)), k.room, 1.0 * d, k.edge)
        }
    }

    @Test
    fun aMarkInARoundedCornerKeepsItsCornerWithinTheArc() {
        // a face rounded 4 dp (all four corners, or only one: each corner its own arc); every mark's box's
        // outer corner never outside its arc, and the arc, not the quarter dp, what kept some of them in
        val grid = listOf(2.0, 2.625, 2.75, 3.0, 3.5).flatMap { d -> listOf(14.0, 14.5, 15.0, 15.5, 16.0, 20.0, 40.0).map { d to it } }
        for (radii in listOf(List(4) { 4.0 }, listOf(0.0, 4.0, 0.0, 0.0), listOf(0.0, 0.0, 0.0, 4.0))) {
            var against = 0
            for ((d, h) in grid) {
                val k = Key(30 * d, h * d, d, radii)
                against += yields(k).sumOf { withinArcs(k, radii, it, "at $d, $h dp, $radii") }
            }
            assertTrue(against >= 10, "against the arc, $radii: $against")
        }
    }

    @Test
    fun aMarkAndItsGlyphMoveAPxAtATimeAsTheGlyphGrows() {
        // the glyph a px wider at a time, from clear of the icon to the icon at the edge and the glyph smaller:
        // neither moves more than a px (and the glyph's own hundredth), its inset eaten a share at a time
        for (d in listOf(2.0, 2.625, 2.75, 3.0, 3.5)) for (r in listOf(0.0, 4.0)) {
            val k = Key(30 * d, 40 * d, d, r)
            var last: KeyMarkYield.Settled? = null
            var px = 0.0
            while (px < 4 * d) {
                val glyph = tall(k, 0.0).let { Ink(it.left, it.top, it.right - 1.0 * d + px, it.bottom) }
                val s = KeyMarkYield.key(glyph, listOf(k.mark(Slot.TopRight)), k.room, 1.0 * d, k.edge)
                val was = last
                if (was != null) {
                    val m = assertNotNull(s.marks[0]); val o = assertNotNull(was.marks[0])
                    val bound = 1.0 + 0.01 * glyph.width + 0.01
                    val moved = maxOf(abs(m.left - o.left), abs(m.top - o.top), abs(m.right - o.right), abs(m.bottom - o.bottom))
                    val glyphMoved = maxOf(abs(s.glyph.left - was.glyph.left), abs(s.glyph.right - was.glyph.right) - 1.0)
                    assertTrue(moved <= bound && glyphMoved <= bound, "at $d, r $r, $px px: $o to $m, ${was.glyph} to ${s.glyph}")
                }
                last = s
                px += 1.0
            }
        }
    }

    @Test
    fun twoMarksMeetingAcrossTheKeyPartTheLeastWay() {
        // top right and bottom left at their least: a quarter dp across each other, a dp and a quarter over each
        // other; up and down parts them too, sideways is the least: an eighth of a dp each, their tops as they were
        for (d in listOf(2.0, 2.625, 2.75, 3.0, 3.5)) {
            val k = Key(17.75 * d, 14.75 * d, d)
            val glyph = Ink(k.room.right - 1.0, k.room.bottom - 1.0, k.room.right, k.room.bottom)
            val s = KeyMarkYield.key(glyph, listOf(k.mark(Slot.TopRight), k.mark(Slot.BottomLeft, KeyMarkYield.DROP_DOWN_SHORTCUT)), k.room, 1.0 * d, k.edge)
            val at = "at $d"
            val up = assertNotNull(s.marks[0], "kept $at")
            val down = assertNotNull(s.marks[1], "kept $at")
            near(k.room.top, up.top, "the top one not up $at")
            near(k.room.bottom, down.bottom, "the bottom one not down $at")
            assertTrue(up.right > k.room.right && up.right <= k.room.right + 0.13 * d, "the top one right an eighth of a dp $at: $up")
            assertTrue(down.left < k.room.left && down.left >= k.room.left - 0.13 * d, "the bottom one left an eighth of a dp $at: $down")
        }
    }

    @Test
    fun aLettersIconTakesTheAxisThatLeavesTheLettersLargest() {
        // a letter 60 dp wide and 10 dp tall from its baseline, a top left icon at its least a dp and three
        // quarters over it at the top (a dp past its top inset) and three dp and a quarter at its left (a dp and
        // a half past its side inset): up leaves the least in px, but the letters, smaller about their
        // baseline's middle, clear the rest across at 0.95 of their size, over it only at 0.9
        for (d in listOf(2.0, 2.625, 2.75, 3.0, 3.5)) {
            val k = Key(70 * d, 20 * d, d)
            val gap = 1.0 * d
            val letter = Ink(6.75 * d, 7.25 * d, 66.75 * d, 17.25 * d)
            val key = KeyMarkYield.LetterKey(letter, letter.bottom, listOf(k.mark(Slot.TopLeft)), k.room, k.edge)
            val at = "at $d"
            val eaten = KeyMarkYield.letters(listOf(key), gap)
            assertTrue(eaten.scale > 0.94 && eaten.scale < 0.96, "the letters at 0.95 $at: ${eaten.scale}")
            val icon = assertNotNull(eaten.keys[0].marks[0], "kept $at")
            near(k.room.top, icon.top, "the icon not up $at")
            assertTrue(icon.left < k.room.left && inside(icon, k.bound), "the icon out to its left $at: $icon")
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
                val before = KeyMarkYield.letters(listOf(key.copy(edge = KeyMarkYield.Edge(k.room))), gap)
                assertTrue(before.scale < 1.0, "no inset to eat: smaller $at")
                val icon = assertNotNull(eaten.keys[0].marks[0], "kept $at")
                assertTrue(inside(icon, k.bound), "within the edge $at: $icon")
                if (over < 0.75) {
                    assertEquals(1.0, eaten.scale, "the letters as they were $at")
                    near(k.room.top - over * d, icon.top, "the icon up as much $at")
                } else {
                    assertTrue(eaten.scale < 1.0 && eaten.scale > before.scale, "smaller, less so $at: ${eaten.scale} ${before.scale}")
                    near(k.bound.top, icon.top, "the icon up to the edge $at")
                }
            }
        }
    }
}
