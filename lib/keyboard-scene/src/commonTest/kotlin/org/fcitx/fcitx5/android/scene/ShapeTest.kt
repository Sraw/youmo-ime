/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Shape.Path.Op.Close
import org.fcitx.fcitx5.android.scene.Shape.Path.Op.LineTo
import org.fcitx.fcitx5.android.scene.Shape.Path.Op.MoveTo
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.ShapePath
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 060-render-v2 §3.1: a key's shape and touch area are shapes, Rect now, Path for what comes (arc)
class ShapeTest {

    @Test
    fun boxes() {
        val b = Box(0.0, 0.0, 10.0, 20.0)
        assertTrue(b.contains(0.0, 0.0))
        assertFalse(b.contains(10.0, 5.0), "half-open: the next key's")
        assertEquals(Box(1.0, 2.0, 7.0, 16.0), b.inset(1.0, 2.0, 3.0, 4.0))
        assertEquals(Box(5.0, 5.0, 10.0, 20.0), b.intersect(Box(5.0, 5.0, 30.0, 30.0)))
        assertEquals(Box(0.0, 0.0, 30.0, 30.0), b.union(Box(5.0, 5.0, 30.0, 30.0)))
        assertFalse(Box(0.0, 0.0, 0.0, 5.0).hasSize)
    }

    @Test
    fun aRoundedRectLeavesItsCornersOut() {
        val r = Shape.Rect(Box(0.0, 0.0, 100.0, 100.0), listOf(20.0, 0.0, 20.0, 0.0))
        assertFalse(r.contains(1.0, 1.0), "outside the top-left curve")
        assertTrue(r.contains(99.0, 1.0), "a square top-right")
        assertTrue(r.contains(50.0, 50.0))
        assertFalse(r.contains(99.0, 99.0), "outside the bottom-right curve")
        assertTrue(r.contains(1.0, 99.0))
        assertEquals(r.box, r.bounds)
    }

    @Test
    fun aPathByEvenOdd() {
        // a triangle with a triangle cut out
        val p = Shape.Path(
            listOf(
                MoveTo(0.0, 0.0), LineTo(100.0, 0.0), LineTo(0.0, 100.0), Close,
                MoveTo(10.0, 10.0), LineTo(30.0, 10.0), LineTo(10.0, 30.0), Close,
            ),
        )
        assertEquals(Box(0.0, 0.0, 100.0, 100.0), p.bounds)
        assertTrue(p.contains(5.0, 50.0))
        assertFalse(p.contains(15.0, 15.0), "the hole")
        assertFalse(p.contains(80.0, 80.0))
        assertFalse(Shape.Path(emptyList()).contains(0.0, 0.0))
    }

    @Test
    fun sceneTextNumbersAreTheSameEverywhere() {
        assertEquals("12", SceneText.n(12.0))
        assertEquals("12.5", SceneText.n(12.5))
        assertEquals("12.05", SceneText.n(12.049))
        assertEquals("-0.25", SceneText.n(-0.25))
        assertEquals("0", SceneText.n(-0.001))
        assertEquals("1078.88", SceneText.n(411 * 2.625))
    }

    @Test
    fun sceneTextOps() {
        val r = Shape.Rect(Box(0.0, 0.0, 1.0, 2.0))
        val c = Argb(0x11223344)
        assertEquals("gradient rect(0,0,1,2) 90 #11223344@0 #11223344@1", SceneText.op(Op.Gradient(r, 90.0, listOf(c to 0.0, c to 1.0))))
        assertEquals("nine-slice rect(0,0,1,2) \"k.png\" 1,2,3,4 3", SceneText.op(Op.NineSlice(r, "k.png", listOf(1, 2, 3, 4), 3)))
        assertEquals("picture rect(0,0,1,2) blur=- brightness=50", SceneText.op(Op.Picture(r, null, 50.0)))
        assertEquals("ripple rect(0,0,1,2) #11223344", SceneText.op(Op.Ripple(r, c)))
        assertEquals("text \"a\\\"\" 1,2 system 400 3 #11223344 scale-x=0.5 rotate=90", SceneText.op(Op.Text("a\"", 1.0, 2.0, "system", 400, 3.0, c, 0.5, 90.0)))
        assertEquals("icon x 0,0,1,2 #11223344 rotate=45", SceneText.op(Op.Icon("x", r.box, c, 45.0)))
        assertEquals("path(M0,0 L1,0 Z)", SceneText.shape(Shape.Path(listOf(MoveTo(0.0, 0.0), LineTo(1.0, 0.0), Close))))
        val scene = Scene(1.0, 1.0, emptyList(), emptyList(), listOf(SlotNode("handwriting-pad", null, Box(0.0, 0.0, 1.0, 1.0))))
        assertEquals("scene 1x1\nslot handwriting-pad 0,0,1,1\n", SceneText.of(scene))
    }

    // 060-render-p3 T4b: `shape="path(…)"` read as SVG's path data, 0–1 in the face's box
    @Test
    fun aPathReadsAsSvgs() {
        val square = assertNotNull(ShapePath.parse("M0 0 H1 V1 H0 Z"))
        val box = Box(10.0, 20.0, 110.0, 70.0)
        assertEquals(
            listOf(MoveTo(10.0, 20.0), LineTo(110.0, 20.0), LineTo(110.0, 70.0), LineTo(10.0, 70.0), Close),
            square.flatten(box).ops,
        )
        // relative, commas, implicit lines after a move, SVG's ".5.5" (two numbers): the same outline
        assertEquals(square, ShapePath.parse("m0,0 h1 v1 h-1 z"))
        assertEquals(ShapePath.parse("M0 0 L1 0 L1 1 L0 1 Z"), ShapePath.parse("M0 0 1 0 1 1 0 1Z"))
        assertEquals(ShapePath.parse("M0.5 0.5 L1 1 L0 1 Z"), ShapePath.parse("M.5.5L1 1 0 1z"))
        assertEquals(ShapePath.parse("M0.5 0 L1 1 L0 1 Z"), ShapePath.parse("m.5 0 l.5 1 h-1 z"))
        // every point to six decimals (a relative sum's 0.30000000000000004 0.3), a second Z in a row dropped
        assertEquals(ShapePath.parse("M0.1 0 L0.3 0 L0.123457 1 Z"), ShapePath.parse("m.1 0 h.2 L.1234567 1 Z z Z"))
        // a Z goes back to its sub-path's start; two sub-paths, each its own outline
        val two = assertNotNull(ShapePath.parse("M0 0 H.4 V1 Z M.6 0 H1 V1 Z")).flatten(Box(0.0, 0.0, 10.0, 10.0))
        assertEquals(2, two.ops.count { it is MoveTo })
        assertTrue(two.contains(3.5, 5.0) && !two.contains(5.0, 5.0) && two.contains(9.5, 5.0))
        // a curve starts where the last ended, ends where it says
        val curved = assertNotNull(ShapePath.parse("M0 1 Q.5 0 1 1 C1 .5 0 .5 0 1 Z")).flatten(box)
        assertEquals(MoveTo(10.0, 70.0), curved.ops.first())
        assertTrue(LineTo(110.0, 70.0) in curved.ops)
    }

    @Test
    fun aBadPathIsRefused() {
        listOf(
            "", " ", "Z", "L1 1", "0 0 L1 1", // nothing, no move first, numbers with no command
            "M0 0 L1 1", "M0 0 Z", // a line, a point: no outline
            "M0 0 L1 1 Z", "M0 0 L.5 .5 L1 1 Z", "M0 0 H1 M0 1 H1", "m.2 .2 l.1 .1 l.2 .2 z", // there and back, in a line: nothing enclosed
            "M0 0 L1 0 L1 1 Z 0", // a number after Z
            "M0 0 L1.5 0 L1 1 Z", "M0 0 L1 0 L1 -0.1 Z", "M.5 .5 l.6 0 l0 .1 z", // out of the box, relative too
            "M0 0 Q2 0 1 1 Z", // a control point out of it
            "M0 0 L1 0 L1", "M0 0 C1 0 1 1 Z", // too few numbers
            "M0 0 A.5 .5 0 0 1 1 1 Z", "M0 0 S1 1 0 1 Z", "M0 0 L1e0 0 L1 1 Z", "M0 0 L1 0 L1 1 Z x", // arcs, smooth curves, exponents
            "M0 0 L. 0 L1 1 Z", "M0 0 L- 0 L1 1 Z",
        ).forEach { assertNull(ShapePath.parse(it), "\"$it\"") }
        // 256 points at most, a curve's control points counted
        val lines = (1..254).joinToString(" ") { "L${it % 2} ${it % 3 / 2.0}" }
        assertNotNull(ShapePath.parse("M0 0 L1 1 $lines Z"), "256")
        assertNull(ShapePath.parse("M0 0 L1 1 $lines L0 1 Z"), "257")
        assertNull(ShapePath.parse("M0 0 L1 1 " + (1..85).joinToString(" ") { "C0 0 1 1 0 1" } + " Z"), "2 + 255")
        assertNull(ShapePath.parse("M0 0 L1 1 L0 1 Z" + " ".repeat(ShapePath.MAX_LENGTH)), "too long")
    }

    @Test
    fun aCurveIsFlattenedWithinHalfAPixel() {
        val w = 400.0
        val h = 300.0
        val box = Box(0.0, 0.0, w, h)
        val paths = listOf(
            "M0 1 Q.5 0 1 1 Z", "M0 .5 C0 0 1 0 1 .5 C1 1 0 1 0 .5 Z", "M0 0 C1 0 0 1 1 1 L0 1 Z", "M.1 .1 Q.11 .1 .12 .1 L.1 .2 Z",
            "M0 0 C1 1 0 1 1 0 Z",
        )
        for (data in paths) {
            val p = assertNotNull(ShapePath.parse(data))
            val pts = p.flatten(box).ops.mapNotNull {
                when (it) {
                    is MoveTo -> it.x to it.y
                    is LineTo -> it.x to it.y
                    Close -> null
                }
            }
            // each curve sampled finely: every sample within half a pixel of the polyline
            var x = 0.0
            var y = 0.0
            for (s in p.segments) {
                val samples = (0..1000).map { it / 1000.0 }.mapNotNull { t ->
                    val u = 1 - t
                    when (s) {
                        is ShapePath.Segment.Quad ->
                            (u * u * x + 2 * u * t * s.cx * w + t * t * s.x * w) to (u * u * y + 2 * u * t * s.cy * h + t * t * s.y * h)
                        is ShapePath.Segment.Cubic ->
                            (u * u * u * x + 3 * u * u * t * s.c1x * w + 3 * u * t * t * s.c2x * w + t * t * t * s.x * w) to
                                (u * u * u * y + 3 * u * u * t * s.c1y * h + 3 * u * t * t * s.c2y * h + t * t * t * s.y * h)
                        else -> null
                    }
                }
                val worst = samples.maxOfOrNull { (sx, sy) ->
                    pts.zipWithNext().minOf { (a, b) -> ShapePath.distance(sx, sy, a.first, a.second, b.first, b.second) }
                } ?: 0.0
                assertTrue(worst <= ShapePath.TOLERANCE, "$data $s: $worst px off")
                when (s) {
                    is ShapePath.Segment.Move -> s.x to s.y
                    is ShapePath.Segment.Line -> s.x to s.y
                    is ShapePath.Segment.Quad -> s.x to s.y
                    is ShapePath.Segment.Cubic -> s.x to s.y
                    ShapePath.Segment.Close -> null
                }?.let { (ex, ey) ->
                    x = ex * w
                    y = ey * h
                }
            }
            assertTrue(pts.size < 200, "$data: a key's few edges (${pts.size})")
        }
        // a curve with no bend is one edge
        assertEquals(2, assertNotNull(ShapePath.parse("M0 0 Q.5 0 1 0 L1 1 Z")).flatten(box).ops.count { it is LineTo })
        // one curve and its Z enclose something; a line and a sub-path of nothing beside an outline are fine
        assertNotNull(ShapePath.parse("M0 1 Q.5 0 1 1 Z"))
        assertNotNull(ShapePath.parse("M0 0 L1 1 Z M0 0 H1 V1 Z"))
        // crossing itself, its signed area nought, but filling some: a bowtie, a figure of eight
        assertNotNull(ShapePath.parse("M0 0 L1 1 L1 0 L0 1 Z"))
        assertNotNull(ShapePath.parse("M.5 .5 C1 0 1 1 .5 .5 C0 0 0 1 .5 .5 Z"))
        assertNotNull(ShapePath.parse("M0 0 L1 1 V0 L0 1 Z M.2 .2 L.4 .4 Z"), "a line beside it")
    }

    // so many curves from corner to corner of a big box: no more than 1024 points in all, the whole path coarser
    @Test
    fun aFlattenedPathHasAThousandPointsAtMost() {
        // a lens drawn over 42 times and a half: 256 points written, some 40 chords a curve at half a pixel
        val p = assertNotNull(ShapePath.parse("M0 0 " + (1..85).joinToString(" ") { if (it % 2 == 1) "C1 0 1 0 1 1" else "C0 1 0 1 0 0" }))
        val ops = p.flatten(Box(0.0, 0.0, 1000.0, 1000.0)).ops
        val points = ops.count { it !is Close }
        assertTrue(points in ShapePath.MAX_FLAT_POINTS / 2..ShapePath.MAX_FLAT_POINTS, "$points points")
        // each curve still ends where it says, the coarser chords between
        assertEquals(43, ops.count { it == LineTo(1000.0, 1000.0) })
        assertEquals(42, ops.count { it == LineTo(0.0, 0.0) })
        // at a key's size it is under the most: as fine as ever
        val small = p.flatten(Box(0.0, 0.0, 50.0, 40.0)).ops
        assertTrue(small.size in 2 * 85..ShapePath.MAX_FLAT_POINTS, "${small.size} points")
    }

    @Test
    fun aRoundedOutlineAndItsTail() {
        val box = Box(0.0, 0.0, 100.0, 60.0)
        assertEquals(box, ShapePath.roundedOutline(box, Shape.NO_RADII).bounds)
        val round = ShapePath.roundedOutline(box, List(4) { 10.0 })
        assertEquals(box, round.bounds)
        assertFalse(round.contains(0.5, 0.5), "its corner cut")
        // a corner's points on its circle
        for (p in round.ops.filterIsInstance<LineTo>().filter { it.x > 90 && it.y < 10 }) {
            assertTrue(abs(hypot(p.x - 90, p.y - 10) - 10) < 1e-9, "$p")
        }
        val tailed = ShapePath.roundedOutline(box, List(4) { 10.0 }, 8.0 to 12.0)
        assertEquals(Box(0.0, 0.0, 100.0, 72.0), tailed.bounds)
        assertTrue(tailed.contains(50.0, 70.0) && !tailed.contains(42.0, 70.0), "a tail down from its middle")
        // moved: every point as much
        val moved = round.moved(3.0, -2.0)
        assertEquals(round.bounds.moved(3.0, -2.0), moved.bounds)
        assertEquals(round.ops.size, moved.ops.size)
        assertEquals(Shape.Rect(Box(3.0, -2.0, 103.0, 58.0), listOf(1.0, 2.0, 3.0, 4.0)), Shape.Rect(box, listOf(1.0, 2.0, 3.0, 4.0)).moved(3.0, -2.0))
    }
}
