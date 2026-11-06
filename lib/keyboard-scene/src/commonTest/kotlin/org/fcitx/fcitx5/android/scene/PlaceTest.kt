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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 060-render-v2 §6 step 3 and §3: cells, widths, rows; the touch area (hit)
class PlaceTest {
    private val env = Fixtures.portrait

    private fun near(expected: Double, actual: Double, what: String = "") =
        assertTrue(abs(expected - actual) < 1e-6, "$what: expected $expected, was $actual")

    private fun Scene.key(id: String, copy: Int = 0) = keys.first { it.id == id && it.copy == copy }

    @Test
    fun lettersAreATenthOfTheWidthRowsAQuarterOfTheHeightRuleD1() {
        val s = Fixtures.scene(env)
        assertEquals(Box(0.0, 0.0, 108.0, 180.0), s.key("q").bounds)
        assertEquals(Box(972.0, 0.0, 1080.0, 180.0), s.key("p").bounds)
        assertEquals(Box(0.0, 540.0, 129.6, 720.0), s.key("symbols").bounds)
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
        near(129.6 * 2 + 108.0, s.key("space").bounds.left)
        near(3.1 * 108.0, s.key("space").bounds.width)
        // the 🌐 key hidden: the space takes its room too
        val noLang = Fixtures.scene(env.copy(input = Input(langKey = false)))
        near(4.1 * 108.0, noLang.key("space").bounds.width)
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
        near(letter, s.key("q").bounds.width)
        near(letter, s.key("shift").bounds.width, "⇧ as wide as a letter (TextKeyboard.Split)")
        near(e.width - letter * 5, s.key("y").bounds.left)
        assertNotNull(s.keys.firstOrNull { it.id == "g" && it.copy == 1 })
        assertNotNull(s.keys.firstOrNull { it.id == "v" && it.copy == 1 })
        near(e.width - letter * 5, s.key("g", 1).bounds.left)
        assertNull(s.keyAt(e.width / 2, 10.0), "the room between the halves takes no touch")
        // the bottom row scaled: as wide in split letters as in letters, the space across the room
        near(1.2 * letter, s.key("symbols").bounds.width)
        assertEquals("space", s.keyAt(e.width / 2, e.height - 1)?.id)
    }

    @Test
    fun splitAfterKeysFollowTheRightHalf() {
        val e = Fixtures.landscape.copy(input = Input(ime = "table", table = "wubi", tableExtra = "semicolon"))
        val s = Fixtures.scene(e)
        val letter = e.width * SplitKeyboard.Sizes(914).letter
        near(e.width, s.key("semicolon").bounds.right)
        near(e.width - letter * 6, s.key("g", 1).bounds.left, "the right half six keys, the room giving way")
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
        s.keys.forEach { near(letter, it.bounds.width, it.id) }
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
}
