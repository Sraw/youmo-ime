/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 060-render-v2 §6 step 3 and §3: cells, widths, rows; the touch area (hit)
class PlaceTest {
    private val env = Fixtures.portrait

    // as computed: a value the engine does not put on a pixel (the board's own edges, a sum of units)
    private fun near(expected: Double, actual: Double, what: String = "") =
        assertTrue(abs(expected - actual) <= 1e-6, "$what: expected $expected, was $actual")

    // a key's edge inside the board: on the whole pixel nearest where it falls (Place's edges)
    private fun edge(expected: Double, actual: Double, what: String = "") = near(floor(expected + 0.5), actual, what)

    // a key [width] wide from [left]: its two edges each so, so its width within a pixel of [width]
    private fun span(left: Double, width: Double, actual: Box, what: String = "") {
        edge(left, actual.left, "$what left")
        edge(left + width, actual.right, "$what right")
    }

    private fun Scene.key(id: String, copy: Int = 0) = keys.first { it.id == id && it.copy == copy }

    @Test
    fun lettersAreATenthOfTheWidthRowsAQuarterOfTheHeightRuleD1() {
        val s = Fixtures.scene(env)
        assertEquals(Box(0.0, 0.0, 108.0, 180.0), s.key("q").bounds)
        assertEquals(Box(972.0, 0.0, 1080.0, 180.0), s.key("p").bounds)
        // 129.6 on a whole pixel
        assertEquals(Box(0.0, 540.0, 130.0, 720.0), s.key("symbols").bounds)
    }

    @Test
    fun aShortRowIsCentredItsBlanksTheEndKeysHitRuleA1() {
        // 双拼's second row without ; (pinyin): nine letters, half a letter each side (0.05 of the width)
        val s = Fixtures.scene(env)
        near(54.0, s.key("a").bounds.left, "A's cell")
        near(1026.0, s.key("l").bounds.right, "L's cell")
        assertEquals(0.0, (s.key("a").hit as Shape.Rect).box.left, "the blank before A is A's (user's P0 decision)")
        assertEquals(1080.0, (s.key("l").hit as Shape.Rect).box.right)
        assertEquals("a", s.keyAt(10.0, 200.0)?.id)
        assertEquals("l", s.keyAt(1075.0, 200.0)?.id)
        // with ; there is no blank
        val shuangpin = Fixtures.scene(env.copy(input = Input(ime = "shuangpin", schemeTypes = setOf("semicolon"))))
        assertEquals(0.0, shuangpin.key("a").bounds.left)
        assertEquals("semicolon", shuangpin.keyAt(1075.0, 200.0)?.id)
    }

    @Test
    fun theSpaceFillsWhatIsLeft() {
        val s = Fixtures.scene(env)
        // 1.2 + 1.2 + 1 + fill + 1 + 1 + 1.5 = 10: fill 3.1
        span(129.6 * 2 + 108.0, 3.1 * 108.0, s.key("space").bounds)
        // the 🌐 key hidden: the space takes its room too
        val noLang = Fixtures.scene(env.copy(input = Input(langKey = false)))
        span(129.6 * 2 + 108.0, 4.1 * 108.0, noLang.key("space").bounds)
    }

    // as the app's views were laid out: rows 175.5 px tall are 176, 175, 176, 175, a letter's face and
    // text where its view drew them; the board's own edges where they are
    @Test
    fun edgesInsideTheBoardOnWholePixels() {
        for (e in listOf(env.copy(height = 702.0), Fixtures.landscape, env.copy(width = 1079.0, height = 701.0))) {
            val s = Fixtures.scene(e)
            for (k in s.keys) {
                for (v in listOf(k.bounds.left, k.bounds.top, k.bounds.right, k.bounds.bottom)) {
                    assertTrue(v == 0.0 || v == e.width || v == e.height || v == kotlin.math.floor(v), "${k.id}: $v")
                }
            }
        }
        val s = Fixtures.scene(env.copy(height = 702.0))
        assertEquals(listOf(0.0, 176.0, 351.0, 527.0), listOf("q", "a", "z", "space").map { s.key(it).bounds.top })
    }

    @Test
    fun theHitCoversEveryPointOnceTheGapsInsideKeysIncluded() {
        val s = Fixtures.scene(env)
        var y = 1.0
        while (y < env.height) {
            var x = 0.5
            while (x < env.width) {
                val hits = s.keys.count { it.hit.contains(x, y) }
                assertEquals(1, hits, "($x, $y)")
                x += 7.0
            }
            y += 11.0
        }
        // between two faces (the theme's inset) is still a key's
        assertEquals("q", s.keyAt(107.0, 5.0)?.id)
        assertEquals("w", s.keyAt(108.0, 5.0)?.id)
        // a finger's centre reported a pixel out counts as on the edge (L4)
        assertEquals("q", s.keyAt(-1.0, 5.0)?.id)
        assertEquals(s.keys.last { it.bounds.left == 0.0 }.id, s.keyAt(5.0, env.height + 1)?.id)
        assertEquals("p", s.keyAt(env.width, 5.0)?.id)
    }

    @Test
    fun indentsGapsHeightsAndOverflow() {
        val l = LayoutReader.layout(
            """
            <keyboard xmlns="${LayoutReader.NAMESPACE}" id="t" name="t" family="symbols" columns="10">
              <row height="2" indent="1" end-indent="1"><key id="a" label="a" width="2"/><gap width="2"/><key id="b" label="b" width="2"/></row>
              <row><key id="c" label="c" width="fill"/><key id="d" label="d" width="fill"/><gap width="fill"/></row>
            </keyboard>
            """.trimIndent(),
        ).ok()
        val s = Fixtures.scene(env, l)
        val a = s.key("a")
        // 2 + 2 + 2 + the indents' 2 = 8: one unit of blank each side, then the indent
        assertEquals(Box(216.0, 0.0, 432.0, 480.0), a.bounds)
        assertEquals(Box(0.0, 0.0, 432.0, 480.0), (a.hit as Shape.Rect).box, "an indent's blank is the end key's too")
        assertEquals(Box(648.0, 0.0, 1080.0, 480.0), (s.key("b").hit as Shape.Rect).box)
        assertNull(s.keyAt(500.0, 100.0), "a <gap> takes no touch")
        near(360.0, s.key("c").bounds.width, "three fills share the row")
        assertNull(s.keyAt(900.0, 600.0), "the gap at the row's end is not a key's")
        val over = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"10\">" +
                "<row><key id=\"a\" label=\"a\" width=\"6\"/><key id=\"b\" label=\"b\" width=\"6\"/></row></keyboard>",
        ).ok()
        val p = org.fcitx.fcitx5.android.scene.engine.SceneBuilder(Fixtures.materialLight, env, FakeMeasurer).build(over).problems()
        assertTrue(p.single().message.contains("12 units wide"), "$p")
    }

    @Test
    fun slotsArePlacedNotDrawn() {
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"10\">" +
                "<row><slot id=\"pad\" kind=\"handwriting-pad\" width=\"8\"/><key id=\"a\" label=\"a\" width=\"2\"/></row></keyboard>",
        ).ok()
        val s = Fixtures.scene(env, l)
        assertEquals(Box(0.0, 0.0, 864.0, 720.0), s.slots.single().bounds)
        assertEquals("handwriting-pad", s.slots.single().kind)
        assertNull(s.keyAt(10.0, 10.0))
    }

    @Test
    fun anchorAndUpRuleV2Section31() {
        val q = Fixtures.scene(env).key("q")
        assertEquals(Point(54.0, 90.0), q.anchor)
        assertEquals(Point(0.0, -1.0), q.up)
    }

    // rules A §8 / D §2
    @Test
    fun splitHalvesFiveKeysEachGAndVOnBoth() {
        val e: Env = Fixtures.landscape
        val s = Fixtures.scene(e)
        val sizes = SplitKeyboard.Sizes(914)
        near(0.4, sizes.gap, "914 dp: 1 - 480/914 over the most, 0.4")
        val letter = e.width * sizes.letter
        span(0.0, letter, s.key("q").bounds)
        span(0.0, letter, s.key("shift").bounds, "⇧ as wide as a letter (TextKeyboard.Split)")
        edge(e.width - letter * 5, s.key("y").bounds.left)
        assertNotNull(s.keys.firstOrNull { it.id == "g" && it.copy == 1 })
        assertNotNull(s.keys.firstOrNull { it.id == "v" && it.copy == 1 })
        edge(e.width - letter * 5, s.key("g", 1).bounds.left)
        assertNull(s.keyAt(e.width / 2, 10.0), "the room between the halves takes no touch")
        // the bottom row scaled: as wide in split letters as in letters, the space across the room
        span(0.0, 1.2 * letter, s.key("symbols").bounds)
        assertEquals("space", s.keyAt(e.width / 2, e.height - 1)?.id)
    }

    @Test
    fun splitAfterKeysFollowTheRightHalf() {
        val e = Fixtures.landscape.copy(input = Input(ime = "table", table = "wubi", tableExtra = "semicolon"))
        val s = Fixtures.scene(e)
        val letter = e.width * SplitKeyboard.Sizes(914).letter
        near(e.width, s.key("semicolon").bounds.right)
        edge(e.width - letter * 6, s.key("g", 1).bounds.left, "the right half six keys, the room giving way")
    }

    @Test
    fun aBoardThatDoesNotSplitGoesToASide() {
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"10\">" +
                "<row><key id=\"a\" label=\"a\" width=\"10\"/></row></keyboard>",
        ).ok()
        val e = Fixtures.landscape
        val s = Fixtures.scene(e, l)
        // rules D §2: docked right, 42% of 914 dp
        val w = SplitKeyboard.dockWidth(914) * e.density
        near(w, s.key("a").bounds.width)
        near(e.width, s.key("a").bounds.right)
        val side = s.slots.single { it.kind == SceneBuilder.SIDE_BUTTONS }
        near(e.width - w, side.bounds.right)
        // touches left of it are not a key's
        assertNull(s.keyAt(5.0, 5.0))
        val whole = Fixtures.scene(e.copy(split = false), l)
        near(e.width, whole.key("a").bounds.width)
    }

    @Test
    fun oneHandedBoardsGoToTheirSide() {
        val left = Fixtures.scene(env.copy(oneHanded = SplitKeyboard.Dock.LEFT))
        val w = (env.widthDp * SplitKeyboard.ONE_HANDED_SHARE).toInt() * env.density
        near(w, left.board.right)
        near(0.0, left.key("q").bounds.left)
        near(w, left.keys.maxOf { it.bounds.right })
        val right = Fixtures.scene(env.copy(oneHanded = SplitKeyboard.Dock.RIGHT))
        near(env.width - w, right.board.left)
        near(env.width - w, right.keys.minOf { it.bounds.left })
        assertEquals(1, right.slots.count { it.kind == SceneBuilder.SIDE_BUTTONS })
    }

    @Test
    fun aHalvesRowOfOtherThanNineOrTenKeysIsScaled() {
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"8\" on-split=\"split\">" +
                "<row split=\"halves\">" + (1..8).joinToString("") { "<key id=\"k$it\" label=\"$it\"/>" } + "</row></keyboard>",
        ).ok()
        val e = Fixtures.landscape
        val s = Fixtures.scene(e, l)
        assertTrue(s.warnings.single().message.contains("not 9 or 10"), "${s.warnings}")
        val letter = e.width * SplitKeyboard.Sizes(914).letter
        // each a split letter wide, the row centred: no room between halves
        s.keys.forEachIndexed { i, k -> span(e.width / 2 + (i - 4) * letter, letter, k.bounds, k.id) }
        near(e.width / 2, (s.key("k4").bounds.right + s.key("k5").bounds.left) / 2)
    }

    @Test
    fun anItemAcrossRowsKeepsItsSideOfTheRowsBelow() {
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"10\">" +
                "<row><slot id=\"side\" kind=\"nine-side\" width=\"2\" rows=\"2\"/><key id=\"a\" label=\"a\" width=\"fill\"/></row>" +
                "<row><key id=\"b\" label=\"b\" width=\"4\"/><key id=\"c\" label=\"c\" width=\"4\"/></row>" +
                "<row><key id=\"d\" label=\"d\" width=\"10\"/></row></keyboard>",
        ).ok()
        val s = Fixtures.scene(env, l)
        val unit = env.width / 10
        val side = s.slots.single { it.id == "side" }.bounds
        near(0.0, side.left)
        near(2 * unit, side.right)
        near(s.key("b").bounds.bottom, side.bottom, "two rows tall")
        near(2 * unit, s.key("b").bounds.left, "the row below starts at its edge")
        near(2 * unit, s.key("b").hit.let { (it as Shape.Rect).box.left }, "and its hit too")
        near(0.0, s.key("d").bounds.left, "the row under both is whole")
        assertNull(s.keyAt(5.0, side.centerY))
    }

    // a key across rows (0.6.1's number pad's Return): its cell all its rows, its hit the same, the rows under it
    // ending at its edge; its bubble's box (head) its first row's
    @Test
    fun aKeyAcrossRowsTakesItsRowsTouches() {
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"4\">" +
                "<row><key id=\"a\" label=\"a\" width=\"1\"/><key id=\"b\" label=\"b\" width=\"fill\"/><key id=\"del\" label=\"x\" width=\"1\"/></row>" +
                "<row><key id=\"c\" label=\"c\" width=\"fill\"/><key id=\"go\" label=\"go\" width=\"1\" rows=\"3\"/></row>" +
                "<row><key id=\"d\" label=\"d\" width=\"fill\"/></row>" +
                "<row><key id=\"e\" label=\"e\" width=\"1\" rows=\"1\"/><key id=\"f\" label=\"f\" width=\"fill\"/></row></keyboard>",
        ).ok()
        val s = Fixtures.scene(env, l)
        val unit = env.width / 4
        val go = s.key("go")
        near(3 * unit, go.bounds.left)
        near(env.width, go.bounds.right)
        near(s.key("c").bounds.top, go.bounds.top, "its first row's top")
        near(env.height, go.bounds.bottom, "down to the last row's foot")
        near(s.key("c").bounds.bottom, go.head.bottom, "its bubble over its first row")
        near(go.bounds.top, go.head.top)
        assertEquals(go.bounds, (go.hit as Shape.Rect).box, "its whole cell takes its touch")
        for (id in listOf("c", "d", "f")) near(3 * unit, s.key(id).bounds.right, "$id ends at its edge")
        near(3 * unit, (s.key("f").hit as Shape.Rect).box.right, "and its hit too")
        // a touch low in it, in the rows below, is its; beside it, the row's
        assertEquals("go", s.keyAt(env.width - 5, env.height - 5)?.id)
        assertEquals("go", s.keyAt(3 * unit + 1, s.key("d").bounds.centerY)?.id)
        assertEquals("d", s.keyAt(3 * unit - 1, s.key("d").bounds.centerY)?.id)
        // a key of one row: its head is its cell
        assertEquals(s.key("a").bounds, s.key("a").head)
    }

    @Test
    fun aKeyAcrossRowsFirstInItsRowKeepsTheLeftSide() {
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"4\">" +
                "<row><key id=\"tall\" label=\"t\" width=\"1\" rows=\"2\"/><key id=\"a\" label=\"a\" width=\"fill\"/></row>" +
                "<row><key id=\"b\" label=\"b\" width=\"fill\"/></row></keyboard>",
        ).ok()
        val s = Fixtures.scene(env, l)
        val unit = env.width / 4
        near(unit, s.key("b").bounds.left, "the row below starts at its edge")
        near(unit, (s.key("b").hit as Shape.Rect).box.left)
        near(env.height, s.key("tall").bounds.bottom)
        assertEquals("tall", s.keyAt(1.0, env.height - 1)?.id)
    }

    // a key across rows in the middle of its row (061-custom-layout §18): the rows under it go past it, their units counted in
    // the room it leaves; each stretch's end keys reach to its edge
    @Test
    fun aKeyAcrossRowsInsideItsRowLaysOut() {
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"3\">" +
                "<row><key id=\"a\" label=\"a\" width=\"1\"/><key id=\"mid\" label=\"m\" width=\"1\" rows=\"2\"/><key id=\"b\" label=\"b\" width=\"1\"/></row>" +
                "<row><key id=\"c\" label=\"c\" width=\"fill\"/><key id=\"d\" label=\"d\" width=\"fill\"/></row></keyboard>",
        ).ok()
        val s = Fixtures.scene(env, l)
        val unit = env.width / 3
        val mid = s.key("mid")
        edge(unit, mid.bounds.left)
        edge(2 * unit, mid.bounds.right)
        near(env.height, mid.bounds.bottom, "down to the last row's foot")
        near(s.key("a").bounds.bottom, mid.head.bottom, "its bubble over its first row")
        // the row under it: c left of it, d right of it, each a unit (the two units it leaves, shared by the fills)
        near(0.0, s.key("c").bounds.left)
        edge(unit, s.key("c").bounds.right)
        edge(2 * unit, s.key("d").bounds.left)
        near(env.width, s.key("d").bounds.right)
        edge(unit, (s.key("c").hit as Shape.Rect).box.right, "c's touch ends at its edge")
        edge(2 * unit, (s.key("d").hit as Shape.Rect).box.left, "d's begins at its other")
        assertEquals("mid", s.keyAt(1.5 * unit, s.key("c").bounds.centerY)?.id)
    }

    // a key under one across rows that reaches into its room (跨行冲突): a problem, the layout check's too
    @Test
    fun aKeyReachingIntoTheRoomOfOneAcrossRowsIsAProblem() {
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"4\">" +
                "<row><key id=\"a\" label=\"a\" width=\"1\"/><key id=\"mid\" label=\"m\" width=\"1\" rows=\"2\"/><key id=\"b\" label=\"b\" width=\"2\"/></row>" +
                "<row><key id=\"c\" label=\"c\" width=\"1.5\"/><key id=\"d\" label=\"d\" width=\"1\"/></row></keyboard>",
        ).ok()
        val built = SceneBuilder(Fixtures.materialLight, env, FakeMeasurer).build(l)
        assertTrue(built.problems().any { "reaches into" in it.toString() }, "${built.problems()}")
    }

    // 跨列 (061-custom-layout §18 补充): a key n units wide takes n columns, its edges the row above's of n one-unit keys; the
    // layout maker's drag snaps a width to 0.5 of a unit (1 on a board of 100), on these same edges, with no syntax of its own
    @Test
    fun aKeyNUnitsWideTakesNColumns() {
        fun board(columns: Int, top: String, under: String) = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"$columns\"><row>$top</row><row>$under</row></keyboard>",
        ).ok()
        val ones = (0 until 10).joinToString("") { "<key id=\"t$it\" label=\"$it\" width=\"1\"/>" }
        val s = Fixtures.scene(env, board(10, ones, "<key id=\"two\" label=\"2\" width=\"2\"/><key id=\"half\" label=\"h\" width=\"0.5\"/><key id=\"more\" label=\"m\" width=\"1.5\"/><key id=\"rest\" label=\"r\" width=\"fill\"/>"))
        val unit = env.width / 10
        assertEquals(s.key("t0").bounds.left, s.key("two").bounds.left)
        assertEquals(s.key("t1").bounds.right, s.key("two").bounds.right, "two units: two columns")
        span(2 * unit, unit / 2, s.key("half").bounds, "half")
        assertEquals(s.key("t3").bounds.right, s.key("more").bounds.right, "0.5 + 1.5 end on a column's edge")
        assertEquals(s.key("t4").bounds.left, s.key("rest").bounds.left)
        near(env.width, s.key("rest").bounds.right, "fill: the six columns left")
        // a board of 100: a width is a share of the row
        val p = Fixtures.scene(env, board(100, "<key id=\"a\" label=\"a\" width=\"25\"/><key id=\"b\" label=\"b\" width=\"75\"/>", "<key id=\"c\" label=\"c\" width=\"12\"/><key id=\"d\" label=\"d\" width=\"13\"/><key id=\"e\" label=\"e\" width=\"fill\"/>"))
        assertEquals(p.key("a").bounds.right, p.key("d").bounds.right, "12 + 13 = 25")
        span(0.0, env.width * 0.12, p.key("c").bounds, "c")
    }

    // two across rows on one side, the second over the side the first keeps (0.6.0's pack layouts): the room counted once
    @Test
    fun aKeyAcrossRowsBesideOneAcrossMoreRowsTakesItsRoomOnce() {
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"4\">" +
                "<row><key id=\"a\" label=\"a\"/><key id=\"b\" label=\"b\"/><key id=\"c\" label=\"c\"/><key id=\"r\" label=\"r\" rows=\"3\"/></row>" +
                "<row><key id=\"d\" label=\"d\"/><key id=\"e\" label=\"e\"/><key id=\"long\" label=\"l\" rows=\"2\"/></row>" +
                "<row><key id=\"f\" label=\"f\"/><key id=\"g\" label=\"g\"/></row></keyboard>",
        ).ok()
        val built = SceneBuilder(Fixtures.materialLight, env, FakeMeasurer).build(l)
        assertEquals(emptyList(), built.problems().map { it.toString() })
        val s = Fixtures.scene(env, l)
        val unit = env.width / 4
        span(0.0, unit, s.key("f").bounds, "f")
        span(unit, unit, s.key("g").bounds, "g")
        span(2 * unit, unit, s.key("long").bounds, "long")
        near(env.height, s.key("long").bounds.bottom)
        near(env.width, s.key("r").bounds.right)
        // the part in its first row, where its bubble goes, is in the scene's text (the snapshots): one line a key across rows
        val text = SceneText.of(s)
        assertEquals(2, text.lines().count { it.startsWith("  head ") }, text)
        val long = s.key("long")
        assertTrue(long.head.bottom < long.bounds.bottom - 1)
        assertTrue("  head ${SceneText.box(long.head)}" in text, text)
    }
}
