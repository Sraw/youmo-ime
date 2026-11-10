/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.theme.IconPath
import org.fcitx.fcitx5.android.scene.theme.ShapePath.Segment
import org.fcitx.fcitx5.android.scene.theme.ShapePath.Segment.Close
import org.fcitx.fcitx5.android.scene.theme.ShapePath.Segment.Cubic
import org.fcitx.fcitx5.android.scene.theme.ShapePath.Segment.Line
import org.fcitx.fcitx5.android.scene.theme.ShapePath.Segment.Move
import org.fcitx.fcitx5.android.scene.theme.ShapePath.Segment.Quad
import org.fcitx.fcitx5.android.scene.theme.SvgSubset
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IconPathTest {

    private fun ok(d: String): List<Segment> = (IconPath.parse(d) as IconPath.Read.Ok).segments
    private fun bad(d: String): String = (IconPath.parse(d) as IconPath.Read.Bad).message

    @Test
    fun relativeAndShorthandCommandsAreMadeAbsolute() {
        assertEquals(
            listOf(Move(1.0, 2.0), Line(4.0, 2.0), Line(4.0, 6.0), Line(5.0, 7.0), Line(6.0, 8.0), Close, Line(2.0, 3.0)),
            ok("M1 2 H4 v4 l1 1 1 1 z l1 1"),
        )
        // further pairs after a move are lines, relative after a relative one
        assertEquals(listOf(Move(1.0, 1.0), Line(2.0, 3.0)), ok("m1 1 1 2"))
        assertEquals(listOf(Move(0.0, 0.0), Quad(1.0, 1.0, 2.0, 0.0), Cubic(3.0, 0.0, 4.0, 1.0, 5.0, 1.0)), ok("M0 0q1 1 2 0c1 0 2 1 3 1"))
    }

    @Test
    fun smoothCurvesReflectTheLastControlPoint() {
        // S after C reflects its second control point about the current point; after anything else it is the current point
        assertEquals(
            listOf(Move(0.0, 0.0), Cubic(0.0, 1.0, 1.0, 1.0, 2.0, 0.0), Cubic(3.0, -1.0, 4.0, 1.0, 5.0, 0.0), Line(6.0, 0.0), Cubic(6.0, 0.0, 7.0, 1.0, 8.0, 0.0)),
            ok("M0 0C0 1 1 1 2 0S4 1 5 0L6 0S7 1 8 0"),
        )
        assertEquals(
            listOf(Move(0.0, 0.0), Quad(1.0, 1.0, 2.0, 0.0), Quad(3.0, -1.0, 4.0, 0.0), Quad(5.0, 1.0, 6.0, 0.0), Line(7.0, 0.0), Quad(7.0, 0.0, 8.0, 0.0)),
            ok("M0 0Q1 1 2 0T4 0t2 0L7 0T8 0"),
        )
    }

    @Test
    fun anArcIsCubicsOnTheEllipse() {
        // a half circle of radius 1 from (0,0) to (2,0), sweeping clockwise on screen (y down): over the top
        val half = ok("M0 0A1 1 0 0 1 2 0")
        assertEquals(3, half.size, "$half") // a move and two quarter turns
        val ends = half.filterIsInstance<Cubic>()
        assertEquals(listOf(1.0 to -1.0, 2.0 to 0.0), ends.map { r(it.x) to r(it.y) })
        // the mid point of each cubic lies on the circle about (1, 0)
        var px = 0.0
        var py = 0.0
        for (c in ends) {
            val mx = (px + 3 * c.c1x + 3 * c.c2x + c.x) / 8
            val my = (py + 3 * c.c1y + 3 * c.c2y + c.y) / 8
            assertTrue(abs(hypot(mx - 1, my) - 1) < 1e-3, "$mx,$my")
            px = c.x
            py = c.y
        }
        // the flags with nothing between them, as minifiers write them: "0 011 1" is flags 0, 1 and then 1 1
        assertEquals(ok("M0 0a1 1 0 0 1 1 1"), ok("M0 0a1 1 0 011 1"))
        // the large arc of a full turn's worth splits in four at most
        assertTrue(ok("M0 0A1 1 0 1 1 0.01 0").size <= 1 + IconPath.MAX_ARC_CUBICS)
    }

    @Test
    fun degenerateArcsAreWhatSvgDraws() {
        // a radius of 0: a straight line; to its own start: nothing; radii too small: scaled up till they reach (F.6.6)
        assertEquals(listOf(Move(0.0, 0.0), Line(3.0, 4.0)), ok("M0 0A0 1 0 0 1 3 4"))
        assertEquals(listOf(Move(1.0, 1.0)), ok("M1 1A5 5 0 0 1 1 1"))
        val small = ok("M0 0A0.1 0.1 0 0 1 4 0").filterIsInstance<Cubic>()
        assertEquals(2, small.size) // scaled to radius 2: a half circle
        assertEquals(-2.0, r(small[0].y))
        // negative radii are their size
        assertEquals(ok("M0 0A1 1 0 0 1 2 0"), ok("M0 0A-1 -1 0 0 1 2 0"))
    }

    @Test
    fun badPathDataSaysWhy() {
        assertEquals("a path starts with M", bad("L0 0"))
        assertEquals("a path starts with M", bad("0 0"))
        assertEquals("empty", bad(" "))
        assertEquals("'x' at 4 is no command or number", bad("M0 x"))
        assertEquals("L needs 2 numbers at 7", bad("M0 0L1"))
        assertEquals("'5' at 6: Z takes no numbers", bad("M0 0Z5"))
        assertEquals("'2' at 14 is no arc flag (0 or 1)", bad("M0 0A1 1 0 0 2 1 1"))
        assertEquals("1e999 at 2 is past a number's range", bad("M1e999 0"))
    }

    // SvgSubset's 20 000 numbers make at most some 2857 arcs, each four cubics at most
    @Test
    fun theSegmentsAreBoundedByTheNumbers() {
        val arcs = SvgSubset.MAX_NUMBERS / 7
        val d = "M0 0" + "a1 1 0 1 1 0.001 0".repeat(arcs - 1)
        val read = IconPath.parse(d)
        assertIs<IconPath.Read.Ok>(read)
        assertTrue(read.segments.size <= 1 + (arcs - 1) * IconPath.MAX_ARC_CUBICS, "${read.segments.size}")
        assertTrue(read.segments.size > (arcs - 1) * 3, "${read.segments.size}") // large arcs: more than three quarters each
        assertEquals(IconPath.Read.TooMany, IconPath.parse("M0 0 1 1", budget = 3))
    }

    @Test
    fun theTextIsWhatTheWebDraws() {
        assertEquals("M1 2 L3.5 4 Q1 1 2 2 C1 2 3 4 5 6 Z", IconPath.text(ok("M1 2l2.5 2Q1 1 2 2C1 2 3 4 5 6z")))
    }

    private fun r(v: Double) = kotlin.math.round(v * 1e6) / 1e6 + 0.0
}
