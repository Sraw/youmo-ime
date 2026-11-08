/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.rules.KeyMarkYield
import org.fcitx.fcitx5.android.scene.rules.KeyMarkYield.Ink
import org.fcitx.fcitx5.android.scene.rules.KeyMarkYield.Mark
import org.fcitx.fcitx5.android.scene.rules.KeyMarkYield.Member
import org.fcitx.fcitx5.android.scene.rules.KeyMarks.Slot
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The yield (rules B §7, 060-render-p2 S4e): a mark gives way in its own corner only, never smaller;
 * a class of glyphs one size, [KeyMarkYield.MAX_CLASS_SHRINK] smaller at most; what still meets left
 * out. A key here is a face [w] × [h] px at [d] px a dp, the yield's room 2 dp and 1 dp in, the marks'
 * edge a quarter dp in, a mark an 8 dp icon 2 dp and 1 dp in.
 */
class KeyMarkYieldTest {

    private class Key(val w: Double, val h: Double, val d: Double, val radii: List<Double> = List(4) { 0.0 }) {
        constructor(w: Double, h: Double, d: Double, r: Double) : this(w, h, d, List(4) { r })

        val room = Ink(2 * d, 1 * d, w - 2 * d, h - 1 * d)
        val edge = KeyMarkYield.Edge(Ink(0.0, 0.0, w, h), radii.map { it * d }, 0.25 * d)
        val size = 8 * d

        fun mark(slot: Slot, drop: Int = KeyMarkYield.DROP_SHORTCUT): Mark = Mark(at(slot), slot, drop)

        fun at(slot: Slot): Ink {
            val x = if (slot.left) 2 * d else w - 2 * d - size
            val y = if (slot.bottom) h - 1 * d - size else 1 * d
            return Ink(x, y, x + size, y + size)
        }

        // a glyph [gw] × [gh] px in the middle
        fun glyph(gw: Double, gh: Double) = Ink((w - gw) / 2, (h - gh) / 2, (w + gw) / 2, (h + gh) / 2)

        // a letter's: [glyph], smaller about its middle on its bottom, never moved
        fun letter(glyph: Ink, marks: List<Mark>) = Member(glyph, (glyph.left + glyph.right) / 2, glyph.bottom, marks, room, edge, moves = false)
    }

    private val least = 1.0 - KeyMarkYield.MAX_CLASS_SHRINK
    private val densities = listOf(2.0, 2.625, 2.75, 3.0, 3.5)
    private val corners = listOf(Slot.TopRight, Slot.TopLeft, Slot.BottomRight, Slot.BottomLeft)

    private fun near(e: Double, a: Double, what: String) = assertTrue(abs(e - a) < 0.02, "$what: expected $e, was $a")

    // a glyph as tall as the key, over the top right icon's room by [over] dp
    private fun tall(k: Key, over: Double) = Ink(k.room.left, -k.h / 8, k.room.right - k.size - 1.0 * k.d + over * k.d, k.h * 9 / 8)

    @Test
    fun nothingMovesWhileNothingMeets() {
        for (d in densities) {
            val k = Key(30 * d, 40 * d, d, 4.0)
            val glyph = k.glyph(10 * d, 10 * d)
            val marks = listOf(k.mark(Slot.TopRight, KeyMarkYield.DROP_SYMBOL), k.mark(Slot.BottomLeft))
            val s = KeyMarkYield.key(glyph, marks, k.room, 1.0 * d, k.edge)
            assertEquals(glyph, s.glyph, "at $d")
            assertEquals(marks.map { it.ink }, s.marks, "at $d")
        }
    }

    @Test
    fun aMarkIsNeverInAnotherCorner() {
        // a glyph growing over each corner's icon: each icon in its own corner, its class's size (its own down
        // to the floor), or left out
        val cases = densities.flatMap { d -> listOf(0.0, 4.0).flatMap { r -> corners.map { Triple(d, r, it) } } }
        for ((d, r, slot) in cases) {
            val k = Key(30 * d, 24 * d, d, r)
            for (over in List(32) { it * 0.25 }) {
                val glyph = k.glyph(k.room.width - 2 * k.size + 2 * over * d, k.room.height - 2 * k.size + 2 * over * d)
                val s = KeyMarkYield.key(glyph, listOf(k.mark(slot)), k.room, 1.0 * d, k.edge)
                val at = "at $d, r $r, $slot, $over dp over"
                s.marks[0]?.let { m ->
                    assertTrue(m.width <= k.size + 1e-6 && m.width >= k.size * KeyMarkYield.MIN_MARK_SCALE - 1e-6, "its width $at: ${m.width}")
                    near(m.width, m.height, "square $at")
                    assertEquals(slot.left, m.left + m.right < k.w, "its side $at: $m")
                    assertEquals(slot.bottom, m.top + m.bottom > k.h, "its edge $at: $m")
                    assertTrue(!m.meets(s.glyph, 1.0 * d), "clear of the glyph $at")
                }
                assertTrue(s.glyph.width >= glyph.width * least - 1e-6, "the glyph no smaller than the class's least $at")
            }
        }
    }

    @Test
    fun aMarkEatsItsInsetBeforeTheGlyphIsSmallerAndIsLeftOutPastTheCap() {
        // the glyph too wide for the icon by its side inset to the edge or less: the icon goes as far sideways
        // as it must, the glyph its size; more, the glyph smaller, no more than the cap; more still, the icon left
        // out and the glyph its size
        for (d in densities) for (r in listOf(0.0, 4.0)) {
            val k = Key(30 * d, 40 * d, d, r)
            val rp = r * d
            val arc = if (rp > d) rp - sqrt(rp * rp - (rp - d) * (rp - d)) else 0.0
            val edge = k.w - maxOf(0.25 * d, arc)
                    var gone = 0
            for (over in listOf(0.5, 1.0, 1.5, 1.75, 2.0, 2.5, 3.0, 4.0, 6.0, 8.0)) {
                val glyph = tall(k, over)
                val s = KeyMarkYield.key(glyph, listOf(k.mark(Slot.TopRight)), k.room, 1.0 * d, k.edge)
                val at = "at $d, r $r, $over dp over"
                val m = s.marks[0]
                if (k.room.right + over * d <= edge + 1e-6) {
                    near(glyph.width, s.glyph.width, "the glyph its size $at")
                    near(k.room.right + over * d, assertNotNull(m, "kept $at").right, "out as far as it must $at")
                    near(k.room.top, m.top, "its top inset kept $at")
                } else if (m != null) {
                    // in along the arc, or the glyph smaller
                    assertTrue(!m.meets(s.glyph, 1.0 * d), "clear $at")
                    assertTrue(m.right <= k.w - 0.25 * d + 1e-6, "no further than the edge $at")
                } else {
                    // past saving: its class asks nothing of it, the glyph its size (060-render-p2 S4e)
                    near(glyph.width, s.glyph.width, "left out, the glyph its size $at")
                    gone++
                }
            }
            assertTrue(gone > 0, "left out past the cap at $d, r $r")
        }
    }

    @Test
    fun aClassIsOneSizeTheMostAnyKeyNeedsUpToTheCap() {
        for (d in densities) {
            val k = Key(30 * d, 40 * d, d)
            val clear = k.letter(k.glyph(10 * d, 10 * d), listOf(k.mark(Slot.TopRight)))
            val lean = tall(k, 2.5)
            val small = k.letter(lean, listOf(k.mark(Slot.TopRight)))
            val g = KeyMarkYield.group(listOf(clear, small), 1.0 * d)
            val at = "at $d"
            assertTrue(g.scale < 1.0 && g.scale >= least - 1e-9, "smaller, no more than the cap $at: ${g.scale}")
            near(clear.glyph.width * g.scale, g.keys[0].glyph.width, "the clear key's glyph as much smaller $at")
            near(lean.width * g.scale, g.keys[1].glyph.width, "the needing key's $at")
            assertNotNull(g.keys[0].marks[0], "the clear key's mark kept $at")
            // nothing needing: no smaller; one no shrink saves: none for it, its mark left out, the others kept
            assertEquals(1.0, KeyMarkYield.group(listOf(clear), 1.0 * d).scale, at)
            val hopeless = k.letter(Ink(k.room.left, -k.h, k.w, 2 * k.h), listOf(k.mark(Slot.TopRight)))
            val h = KeyMarkYield.group(listOf(clear, hopeless), 1.0 * d)
            assertEquals(1.0, h.scale, "none for it $at")
            assertNull(h.keys[1].marks[0], "the hopeless key's mark left out $at")
            assertNotNull(h.keys[0].marks[0], "the clear key's kept $at")
        }
    }

    @Test
    fun aClassNeedsNoMoreThanItsNeediestKeyAndNeverJumps() {
        // a letter growing a px at a time over its mark: the class smaller by about as much each time, never a
        // step of more than a px's worth, then capped and the mark left out (for good)
        for (d in densities) for (r in listOf(0.0, 4.0)) {
            val k = Key(30 * d, 40 * d, d, r)
            var last: KeyMarkYield.Group? = null
            var gone = false
            var px = 0.0
            while (px < 12 * d) {
                val glyph = tall(k, 0.0).let { Ink(it.left, it.top, it.right - 1.0 * d + px, it.bottom) }
                val g = KeyMarkYield.group(listOf(k.letter(glyph, listOf(k.mark(Slot.TopRight)))), 1.0 * d)
                val at = "at $d, r $r, $px px"
                val m = g.keys[0].marks[0]
                if (m == null) gone = true else assertTrue(!gone, "left out, then kept again $at")
                // smaller about its middle: a px more of it on one side, two px of its width
                val was = last?.scale ?: g.scale
                assertTrue(was - g.scale <= 2.0 / glyph.width + 0.002, "no jump in size $at: $was to ${g.scale}")
                // a px out, and down along the arc as it goes, steeper near its end: no more than a dp
                val om = last?.keys?.get(0)?.marks?.get(0)
                if (om != null && m != null) assertTrue(abs(m.left - om.left) <= 1.0 + 1e-6 && abs(m.top - om.top) <= d, "the mark a px out at most $at: $om to $m")
                last = g
                px += 1.0
            }
            assertTrue(gone, "left out at last at $d, r $r")
        }
    }

    @Test
    fun theMostExpendableIsLeftOutFirst() {
        // a key's glyph over its three marks' corners: the down shortcut first, then the shortcut, the symbol last
        for (d in densities) {
            val k = Key(30 * d, 24 * d, d)
            val marks = listOf(
                k.mark(Slot.TopRight, KeyMarkYield.DROP_SYMBOL),
                k.mark(Slot.TopLeft, KeyMarkYield.DROP_SHORTCUT),
                k.mark(Slot.BottomLeft, KeyMarkYield.DROP_DOWN_SHORTCUT),
            )
            // over the left ones only: both left out, the symbol kept
            val left = Ink(-k.w, -k.h, k.w / 2 - 2 * d, 2 * k.h)
            val s = KeyMarkYield.key(left, marks, k.room, 1.0 * d, k.edge)
            assertNotNull(s.marks[0], "the symbol kept at $d")
            assertNull(s.marks[1], "the shortcut left out at $d")
            assertNull(s.marks[2], "the down shortcut left out at $d")
            // over all: all left out, the symbol too (the long press still works)
            val all = KeyMarkYield.key(Ink(-k.w, -k.h, 2 * k.w, 2 * k.h), marks, k.room, 1.0 * d, k.edge)
            assertEquals(listOf<Ink?>(null, null, null), all.marks, "at $d")
            // two meeting each other, the key too short for both: the down one left out
            val short = Key(30 * d, 14 * d, d)
            val two = KeyMarkYield.key(short.glyph(2.0, 2.0), listOf(short.mark(Slot.TopLeft), short.mark(Slot.BottomLeft, KeyMarkYield.DROP_DOWN_SHORTCUT)), short.room, 1.0 * d, short.edge)
            assertNotNull(two.marks[0], "the up one kept at $d")
            assertNull(two.marks[1], "the down one left out at $d")
        }
    }

    @Test
    fun twoMarksMeetingEachOtherEatTheirInsetsBeforeEitherIsLeftOut() {
        // the small phone's ⌫: 2.75 px a dp, two 8 dp icons in a face half a px short of the room they need: they
        // eat half a px of their insets and both are kept
        val d = 2.75
        val k = Key(54 * d, 2 * 8 * d + 2 * d - 0.5, d)
        val s = KeyMarkYield.key(k.glyph(2.0, 2.0), listOf(k.mark(Slot.TopLeft), k.mark(Slot.BottomLeft, KeyMarkYield.DROP_DOWN_SHORTCUT)), k.room, d, k.edge)
        val up = assertNotNull(s.marks[0])
        val down = assertNotNull(s.marks[1])
        assertTrue(up.bottom <= down.top + 1e-6, "apart: $up, $down")
        // half a px out into their insets in all, no further than their edges
        near(0.5, (k.room.top - up.top) + (down.bottom - k.room.bottom), "half a px out")
        assertTrue(up.top >= 0.25 * d - 1e-6 && down.bottom <= k.h - 0.25 * d + 1e-6, "within the edges: $up, $down")
    }

    // the marks of [s] each with its corners within [k]'s arcs (dp: top left, top right, bottom right, bottom left)
    private fun withinArcs(k: Key, s: KeyMarkYield.Settled, at: String) = s.marks.filterNotNull().forEach { m ->
        for ((n, c) in listOf(m.left to m.top, m.right to m.top, m.right to m.bottom, m.left to m.bottom).withIndex()) {
            val r = k.radii[n] * k.d
            val cx = if (n == 0 || n == 3) r else k.w - r
            val cy = if (n < 2) r else k.h - r
            val outX = if (n == 0 || n == 3) c.first < cx else c.first > cx
            val outY = if (n < 2) c.second < cy else c.second > cy
            if (outX && outY) assertTrue(sqrt((c.first - cx) * (c.first - cx) + (c.second - cy) * (c.second - cy)) <= r + 1e-6, "within the arc $at: $m")
        }
    }

    @Test
    fun aMarkInARoundedCornerKeepsItsCornersWithinTheArcs() {
        // faces rounded 4 and 6 dp (all four corners, or one), short and tall: every mark's corners, its far
        // ones too (a short key's top mark reaches its bottom arc), within the arcs, however it gave way
        for (d in densities) for (h in listOf(10.0, 12.0, 14.5, 16.0, 20.0, 40.0)) for (radii in listOf(List(4) { 4.0 }, List(4) { 6.0 }, listOf(0.0, 4.0, 0.0, 0.0))) {
            val k = Key(30 * d, h * d, d, radii)
            for ((over, slot) in listOf(0.0, 0.5, 1.0, 2.0, 3.0).flatMap { o -> corners.map { o to it } }) {
                val glyph = k.glyph(k.room.width - 2 * k.size - 2 * d + 2 * over * d, k.h - 2 * d - 2 * k.size + 2 * over * d)
                withinArcs(k, KeyMarkYield.key(glyph, listOf(k.mark(slot)), k.room, 1.0 * d, k.edge), "at $d, $h dp, $radii, $slot, $over dp over")
            }
            val two = KeyMarkYield.key(k.glyph(2.0, 2.0), listOf(k.mark(Slot.TopLeft), k.mark(Slot.BottomLeft, KeyMarkYield.DROP_DOWN_SHORTCUT)), k.room, 1.0 * d, k.edge)
            withinArcs(k, two, "two, at $d, $h dp, $radii")
        }
    }

    @Test
    fun theMarkMostExpendableIsLeftOutFirst() {
        // a key so short its top and bottom icons meet, a dot of a glyph: the bottom one left out, whichever
        // its corner and whichever comes first, the symbol before none and the down shortcut before the shortcut
        for (d in densities) {
            val k = Key(30 * d, 12 * d, d)
            val g = k.glyph(2.0, 2.0)
            for ((top, bottom) in listOf(KeyMarkYield.DROP_SYMBOL to KeyMarkYield.DROP_SHORTCUT, KeyMarkYield.DROP_SHORTCUT to KeyMarkYield.DROP_DOWN_SHORTCUT)) {
                for ((a, b) in listOf(Slot.TopRight to Slot.BottomRight, Slot.TopLeft to Slot.BottomLeft)) {
                    val s = KeyMarkYield.key(g, listOf(k.mark(a, top), k.mark(b, bottom)), k.room, 1.0 * d, k.edge)
                    assertNotNull(s.marks[0], "at $d, $top kept over $bottom")
                    assertNull(s.marks[1], "at $d, $bottom left out")
                    val r = KeyMarkYield.key(g, listOf(k.mark(b, bottom), k.mark(a, top)), k.room, 1.0 * d, k.edge)
                    assertNull(r.marks[0], "at $d, $bottom left out, first")
                    assertNotNull(r.marks[1], "at $d, $top kept, second")
                }
            }
        }
    }

    @Test
    fun aShortKeysTopMarkGoesAsideNoFurtherThanItsBottomArc() {
        // a face 10 dp tall rounded 4 dp, a glyph as tall as it over the top right icon's room: the icon aside,
        // its bottom right corner (below the bottom arc's top) kept within that arc too, and clear of the glyph
        for (d in densities) for (over in listOf(0.5, 1.0, 1.5)) {
            val k = Key(30 * d, 10 * d, d, 4.0)
            val s = KeyMarkYield.key(tall(k, over), listOf(k.mark(Slot.TopRight)), k.room, 1.0 * d, k.edge)
            val m = s.marks[0] ?: continue
            withinArcs(k, s, "at $d, $over dp over")
            assertTrue(!m.meets(s.glyph, 1.0 * d - 1e-6), "at $d, $over dp over: clear of the glyph: $m ${s.glyph}")
        }
    }

    @Test
    fun aMarkPlacedOutOfItsArcIsMovedInTheLeast() {
        // a 16 dp icon 2 dp in from the side of a face 19 dp tall rounded 8: its bottom corner out of the bottom
        // arc as placed, nothing meeting it: moved in the least, on one axis
        val d = 2.0
        val k = Key(56 * d, 19 * d, d, 8.0)
        val ink = Ink(4 * d, 2 * d, 20 * d, 18 * d)
        val s = KeyMarkYield.key(k.glyph(2.0, 2.0), listOf(Mark(ink, Slot.TopLeft)), k.room, d, k.edge)
        val m = assertNotNull(s.marks[0])
        withinArcs(k, s, "moved in")
        assertTrue(m != ink && maxOf(abs(m.left - ink.left), abs(m.top - ink.top)) < 0.5, "moved, a little: $m")
    }

    @Test
    fun aMarkStillMeetingTheGlyphGoesInAlongTheArcBeforeItIsLeftOut() {
        // a top left icon in a corner rounded 4 dp, a letter the room's width under it, its top a tenth of a px
        // higher at a time: the icon up, then in along the arc, never a jump, then the class (and the icon)
        // smaller, then left out
        for (d in densities) {
            val k = Key(24 * d, 15 * d, d, 4.0)
            val gap = 1.0 * d
            var last: Ink? = null
            var scale: Double? = null
            var along = 0
            var gone = false
            var top = k.room.top + k.size + gap
            while (top > k.room.top + k.size + gap - 4 * d) {
                val glyph = Ink(k.room.left, top, k.room.right, k.room.bottom)
                val g = KeyMarkYield.group(listOf(k.letter(glyph, listOf(k.mark(Slot.TopLeft)))), gap)
                val at = "at $d, the glyph's top at $top"
                // a tenth of a px of the glyph's top a step, the glyph smaller about its bottom: no more than
                // about that tenth over its height, never a jump
                scale?.let { assertTrue(abs(g.scale - it) <= 0.1 / (glyph.bottom - glyph.top) * 1.25 + 1e-3, "the class's step $at: $it to ${g.scale}") }
                scale = g.scale
                val m = g.keys[0].marks[0]
                if (m == null) {
                    // the class asking the less of it the further past saving it (060-render-p2 S4e)
                    assertTrue(g.scale >= least - 1e-9 && g.scale <= 1.0, "left out, the class within its cap $at: ${g.scale}")
                    gone = true
                } else {
                    assertTrue(!gone, "left out, then kept again $at")
                    withinArcs(k, g.keys[0], at)
                    last?.let { o -> assertTrue(maxOf(abs(m.left - o.left), abs(m.top - o.top)) <= 0.5, "no jump $at: $o to $m") }
                    if (m.left > k.room.left + 1e-6) {
                        assertTrue(m.top < k.room.top, "up as it goes in $at: $m")
                        along++
                    }
                    last = m
                }
                top -= 0.1
            }
            assertTrue(along >= 2 && gone, "in along the arc ($along), then left out, at $d")
        }
    }

    @Test
    fun onlyTheMarkClassThatMeetsIsSmaller() {
        // a short key's symbol (top right) meeting the other's below it; a roomy key's top left shortcut:
        // the symbols smaller, the shortcuts their size (060-render-p2 S4e M3)
        for (d in densities) {
            val k = Key(30 * d, 15 * d, d)
            val glyph = k.glyph(2 * d, 2 * d)
            val marks = listOf(k.mark(Slot.TopRight, KeyMarkYield.DROP_SYMBOL), k.mark(Slot.BottomRight, KeyMarkYield.DROP_SYMBOL), k.mark(Slot.TopLeft))
            val short = Member(glyph, (glyph.left + glyph.right) / 2, (glyph.top + glyph.bottom) / 2, marks, k.room, k.edge)
            val b = KeyMarkYield.board(listOf(short, stacked(d, 40.0).second), 1.0 * d)
            assertTrue(b.symbol < 1.0, "the symbols smaller at $d: ${b.symbol}")
            assertEquals(1.0, b.shortcut, "the shortcuts their size at $d")
        }
    }

    @Test
    fun aClassStillSavedAloneIsNotLetGoWithTheOther() {
        // the review's probe: 2 px a dp, a 60 by 80 key, a symbol top right and a shortcut top left, a glyph
        // under both growing leftwards 0.05 px at a time: the symbols' class a little smaller each step, never
        // a jump; the symbol left out only where it is so alone too (060-render-p2 S4e)
        val d = 2.0
        val k = Key(60.0, 80.0, d)
        for (r in listOf(43.0, 44.0, 45.0, 46.0, 47.0)) {
            var was: Double? = null
            var l = 19.0
            while (l >= 12.0) {
                val g = Ink(l, 2.0, r, 78.0)
                val symbol = k.mark(Slot.TopRight, KeyMarkYield.DROP_SYMBOL)
                fun board(marks: List<Mark>) = KeyMarkYield.board(listOf(Member(g, (l + r) / 2, 78.0, marks, k.room, k.edge, moves = false)), 1.0 * d)
                val b = board(listOf(symbol, k.mark(Slot.TopLeft)))
                val at = "at R $r, L $l"
                was?.let { assertTrue(abs(b.symbol - it) <= 0.01, "the symbols' step $at: $it to ${b.symbol}") }
                if (b.keys[0].marks[0] == null) assertNull(board(listOf(symbol)).keys[0].marks[0], "the symbol left out, alone kept $at")
                was = b.symbol
                l -= 0.05
            }
        }
    }

    // a key [hDp] dp tall at [d] with its two left icons, a small glyph in its middle that meets neither
    private fun stacked(d: Double, hDp: Double): Pair<Key, Member> {
        val k = Key(30 * d, hDp * d, d)
        val glyph = k.glyph(2 * d, 2 * d)
        val marks = listOf(k.mark(Slot.TopLeft), k.mark(Slot.BottomLeft, KeyMarkYield.DROP_DOWN_SHORTCUT))
        return k to Member(glyph, (glyph.left + glyph.right) / 2, (glyph.top + glyph.bottom) / 2, marks, k.room, k.edge)
    }

    @Test
    fun aMarkClassIsOneSizeTheMostAnyKeyNeedsUpToItsCap() {
        // a roomy key and a short one whose two 8 dp icons, their insets eaten to a quarter dp, still meet: every
        // shortcut as much smaller, about its corner, the least that parts the short key's; past the cap the
        // down one left out, the class asking the less of the short key the further past (none a band past it,
        // so it never jumps where the key stops being saved); the symbols, none meeting, their size
        for (d in densities) {
            val roomy = stacked(d, 40.0).second
            // the short key needs (h - 0.5) / 16 of its icons' size: that down to the floor, then a band below it
            // where it asks the less the further down, none past it
            val floor = KeyMarkYield.MIN_MARK_SCALE
            val band = KeyMarkYield.HOPELESS_BAND
            fun asked(n: Double) = if (n >= floor) n else 1 - (1 - floor) * ((n - floor + band) / band).coerceIn(0.0, 1.0)
            for ((hDp, scale) in listOf(15.5, 14.75, 13.9, 13.0, 12.0).map { it to asked((it - 0.5) / 16) }) {
                val short = stacked(d, hDp).second
                val b = KeyMarkYield.board(listOf(roomy, short), 1.0 * d)
                val at = "at $d, $hDp dp"
                assertTrue(abs(b.shortcut - scale) < 0.002, "$at: ${b.shortcut} for $scale")
                assertEquals(1.0, b.symbol, at)
                assertEquals(1.0, b.scales.getValue(0), "the glyphs their size $at")
                val inks = b.keys.flatMap { it.marks }.filterNotNull()
                assertTrue(inks.all { abs(it.height - inks.first().height) < 1e-9 }, "one size $at: $inks")
                near(8 * d * b.shortcut, inks.first().height, "the class's $at")
                // the roomy key's icons smaller about their corners: their outer corners where they were
                val r = b.keys[0].marks
                near(roomy.marks[0].ink.left, r[0]!!.left, "top's left $at")
                near(roomy.marks[0].ink.top, r[0]!!.top, "top's top $at")
                near(roomy.marks[1].ink.bottom, r[1]!!.bottom, "bottom's bottom $at")
                val kept = b.keys[1].marks
                if (hDp > 14.5) {
                    assertTrue(kept.all { it != null } && kept[0]!!.bottom <= kept[1]!!.top + 1e-6, "both, apart $at: $kept")
                } else {
                    assertNull(kept[1], "the down one left out $at")
                    assertNotNull(kept[0], at)
                }
            }
        }
    }

    @Test
    fun aGlyphClassGivesWayBeforeAMarkClass() {
        // a letter over its symbol by a little: the letters smaller within their cap, the symbols their size;
        // over by more than the letters' cap clears, the symbols then smaller too
        for (d in densities) {
            val k = Key(30 * d, 40 * d, d)
            var within = 0
            var past = 0
            var over = 0.0
            while (over < 3.0) {
                val glyph = tall(k, over)
                val b = KeyMarkYield.board(listOf(k.letter(glyph, listOf(k.mark(Slot.TopRight, KeyMarkYield.DROP_SYMBOL)))), 1.0 * d)
                val g = b.scales.getValue(0)
                if (g > least + 1e-9) {
                    assertEquals(1.0, b.symbol, "at $d, $over dp over: the letters $g")
                    if (g < 1.0) within++
                } else if (b.symbol < 1.0) {
                    past++
                }
                over += 0.125
            }
            assertTrue(within > 0 && past > 0, "at $d: within $within, past $past")
        }
    }

    @Test
    fun aKeyIsDrawnAsItsEdgesBeforeTheirRoundingSettled() {
        // the same key a fraction of a px off (its edges on whole pixels): an icon that stayed where the key has
        // it, one that moved where it went before the rounding, its glyph moved as far
        for (d in densities) {
            val (_, was) = stacked(d, 40.0)
            val key = was.copy(marks = was.marks.map { it.copy(ink = it.ink.moved(0.4, -0.3)) })
            val b = KeyMarkYield.board(listOf(was), 1.0 * d)
            assertEquals(key.marks.map { it.ink }, KeyMarkYield.onto(key, was, b, b.keys[0]).marks, "unmoved at $d")
            val (_, short) = stacked(d, 15.0)
            val off = short.copy(marks = short.marks.map { it.copy(ink = it.ink.moved(0.4, -0.3)) })
            val s = KeyMarkYield.board(listOf(short), 1.0 * d)
            assertEquals(s.keys[0].marks, KeyMarkYield.onto(off, short, s, s.keys[0]).marks, "moved at $d")
        }
    }
}
