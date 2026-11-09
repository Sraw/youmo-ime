/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Content
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.IconSizes
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.engine.MarkSettings
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.engine.toInk
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.rules.KeyMarkYield
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 060-render-v2 §6 step 5: labels, icons and marks where theme 3.0's views put them (rules B §5–§7)
class ContentTest {
    private val env = Fixtures.portrait
    private val d = Fixtures.DENSITY

    private fun near(e: Double, a: Double, what: String = "") = assertTrue(abs(e - a) < 0.01, "$what: expected $e, was $a")

    private fun ops(s: Scene, id: String, pressed: Boolean = false) = s.keys.first { it.id == id }.ops(pressed)

    private val movingIcons = setOf("delete-sweep", "restore", "select-all", "content-copy", "content-paste", "content-cut", "undo", "redo")

    private fun texts(s: Scene, id: String) = ops(s, id).filterIsInstance<Op.Text>()

    private fun icons(s: Scene, id: String) = ops(s, id).filterIsInstance<Op.Icon>()

    private fun face(s: Scene, id: String) = (s.keys.first { it.id == id }.shape as Shape.Rect).box

    @Test
    fun aLetterCentredByItsInkUprightByTheFontsExtentRuleB5() {
        val s = Fixtures.scene(env)
        val q = texts(s, "q").first { it.text == "q" }
        val size = 23 * d
        near(size, q.size)
        val f = face(s, "q")
        // a single character: its ink (0.05 of the size in, FakeMeasurer) centred across
        near(f.centerX - (0.6 * size - 0.1 * size) / 2 - 0.05 * size, q.x, "x")
        // the font's extent (1.05 above, 0.3 below) centred up and down
        near(f.centerY - (1.35 * size) / 2 + 1.05 * size, q.baseline, "baseline")
        assertEquals(400, q.weight)
        assertEquals(Fixtures.materialLight.colors["keyText"], q.color)
    }

    @Test
    fun longerTextIsCentredByItsAdvance() {
        val s = Fixtures.scene(env)
        val label = texts(s, "space").single()
        val size = 13 * d
        near(size, label.size)
        near(face(s, "space").centerX - ceil(2 * size) / 2, label.x)
        val numbers = texts(s, "numbers").single()
        assertEquals(700, numbers.weight, "text-style=bold")
    }

    @Test
    fun theSymbolInItsCornerRuleB7() {
        val s = Fixtures.scene(env)
        val one = texts(s, "q").first { it.text == "1" }
        val size = Content.SYMBOL_DP * d
        near(size, one.size)
        assertEquals(700, one.weight)
        val f = face(s, "q")
        // TopRight: 4 dp in from the face's right, its ink's right; its box's top at the face's top
        val inkRight = one.x + 0.6 * 1.05 * size - 0.05 * size
        near(f.right - 4 * d, inkRight, "right")
        near(f.top + 1.05 * size, one.baseline, "top")
        assertEquals(Fixtures.materialLight.colors["keyHint"], one.color)
    }

    @Test
    fun theShortcutTopLeftAndTheFunctionKeysUpAndDown() {
        val s = Fixtures.scene(env)
        val redo = icons(s, "y").single()
        assertEquals("redo", redo.name)
        val f = face(s, "y")
        // as its ImageView was laid out: from the pixel its margins reach, whole pixels wide
        near(floor(f.left + 3 * d), redo.box.left)
        near(floor(f.top + 2 * d), redo.box.top)
        near(floor(11 * d + 0.5), redo.box.width)
        val backspace = icons(s, "backspace").map { it.name }
        assertEquals(listOf("backspace", "delete-sweep", "restore").sorted(), backspace.sorted())
        val down = icons(s, "backspace").first { it.name == "restore" }
        assertTrue(abs(face(s, "backspace").bottom - 2 * d - down.box.bottom) < 1.0, "down's at the bottom, within its pixel")
        assertEquals("cursor-move", icons(s, "space").single().name)
    }

    @Test
    fun aShortBackspaceKeepsBothItsIconsEveryShortcutAsMuchSmaller() {
        // 注音大千 on a 360 dp phone at 440 dpi, compact, a little taller than 20%: a ⌫ face 45.5 px tall. Its two
        // 8 dp icons, 22 px each, need 44 with nothing between them and the face's 4 dp corners: every shortcut
        // icon of the board as much smaller (060-render-p2 S4e), no more than the marks' cap, both drawn one over
        // the other; at 20% (43.5 px) past the cap, the down one left out
        val d = 2.75
        fun scene(h: Double) = Forms.scene("zhuyin", Forms.env("zhuyin", Fixtures.compact.copy(width = 990.0, height = h, density = d)))
        assertEquals(setOf("backspace", "delete-sweep"), icons(scene(407.0), "backspace").map { it.name }.toSet(), "at 20%")
        val s = scene(420.0)
        near(45.5, face(s, "backspace").height, "the face")
        val icons = icons(s, "backspace").associate { it.name to it.box }
        assertEquals(setOf("backspace", "delete-sweep", "restore"), icons.keys)
        val up = icons.getValue("delete-sweep")
        val down = icons.getValue("restore")
        assertTrue(up.bottom <= down.top + 0.01, "one over the other: $up $down")
        assertTrue(up.height < 22.0 && up.height >= 22.0 * KeyMarkYield.MIN_MARK_SCALE - 0.01, "smaller, no more than the cap: ${up.height}")
        val sizes = s.keys.flatMap { k -> k.ops(false).filterIsInstance<Op.Icon>().filter { it.name in movingIcons && it.name != k.id } }.map { it.box.width }.toSet()
        assertEquals(setOf(up.width), sizes, "every shortcut icon the same size")
    }

    @Test
    fun twoIconsOneOverTheOtherAreKeptApartWithinTheirFaceOrOneLeftOut() {
        // whatever the density and the keyboard's size, a ⌫'s two icons, where both are drawn, are apart, their
        // boxes a quarter dp in from the face's top and bottom and their corners within the face's rounded ones;
        // drawn no smaller than the marks' cap; one left out only where that is not enough
        // the theme's 4 dp corners, and square ones (where the quarter dp is what stops them)
        val edgeDp = 0.25
        var kept = 0
        var smaller = 0
        var left = 0
        val square = ThemeReader.theme(testData("material-light.xml").replace("</theme>", "<style select=\"key\" radius=\"0dp\"/>\n</theme>")).ok()
        val grid = listOf(Fixtures.materialLight, square).flatMap { t ->
            listOf(2.0, 2.625, 2.75, 3.0, 3.5).flatMap { d ->
                listOf(320.0, 360.0, 393.0, 411.0, 600.0).flatMap { w -> listOf(110.0, 120.0, 130.0, 140.0, 145.0, 148.0, 160.0, 200.0).map { Triple(t, d, w to it) } }
            }
        }
        for ((theme, d, size) in grid) {
            val (wDp, hDp) = size
            val e = Forms.env("zhuyin", Fixtures.compact.copy(width = wDp * d, height = hDp * d, density = d))
            val s = Forms.scene("zhuyin", e, theme)
            val key = s.keys.first { it.id == "backspace" }
            val f = (key.shape as Shape.Rect).box
            val at = "at $d, $wDp × $hDp dp, a face ${f.height} px"
            val icons = icons(s, "backspace").associate { it.name to it.box }
            val up = icons.getValue("delete-sweep")
            val icon = floor(Content.SHORTCUT_ICON_COMPACT_DP * d + 0.5)
            assertTrue(up.height >= icon * KeyMarkYield.MIN_MARK_SCALE - 0.01, "no smaller than the cap $at: $up")
            val down = icons["restore"]
            if (down == null) {
                left++
                continue
            }
            kept++
            if (up.height < icon - 0.01) smaller++
            assertTrue(up.bottom <= down.top + 0.01, "apart $at: $up $down")
            assertTrue(up.top >= f.top + edgeDp * d - 0.01, "up in its face $at: $up")
            assertTrue(down.bottom <= f.bottom - edgeDp * d + 0.01, "down in its face $at: $down")
            iconsWithinArcs(key, at)
        }
        // the sweep met all three: kept as they were, kept smaller, one left out
        assertTrue(kept - smaller >= 20 && smaller >= 10 && left >= 10, "kept $kept (smaller $smaller), left out $left")
    }

    @Test
    fun aCompactZhuyinBackspacesIconNeverFlickers() {
        // compact and small compact zhuyin, a px shorter at a time where ⌫'s icons crowd it: its sweep icon
        // never somewhere, then elsewhere, then back, both steps over 1.5 px (060-render-p2 S4e)
        val small = Forms.compact.copy(width = 990.0 * 2.625 / 2.75, height = 407.0 * 2.625 / 2.75)
        for ((form, d, hi) in listOf(Triple(Forms.compact, 2.75, 324), Triple(small, 2.75, 324), Triple(Forms.compact, 3.5, 412), Triple(small, 3.5, 412))) {
            val k = d / Forms.DENSITY
            val at = (hi downTo hi - 12).map { h ->
                val e = Forms.env("zhuyin", form.copy(width = form.width * k, height = h.toDouble(), density = d))
                h to Forms.scene("zhuyin", e).keys.flatMap { it.ops(false) }.filterIsInstance<Op.Icon>().firstOrNull { it.name == "delete-sweep" }?.box
            }
            var compared = 0
            for (i in 2 until at.size) {
                val (a, b, c) = Triple(at[i - 2].second, at[i - 1].second, at[i].second)
                if (a == null || b == null || c == null) continue
                compared++
                fun step(x: Box, y: Box) = maxOf(abs(x.left - y.left), abs(x.top - y.top))
                assertTrue(!(step(a, b) > 1.5 && step(b, c) > 1.5 && step(a, c) <= 1.5), "flicker at $d, ${form.width}, ${at[i - 1].first} px: $a, $b, $c")
            }
            assertTrue(compared > 0, "the sweep icon drawn three heights running somewhere at $d, ${form.width}")
        }
    }

    @Test
    fun aFloatingBoardsClassesShrinkSmoothlyAndItsIconsKeepWithinTheArcs() {
        // floating qwerty, k14, k18, zhuyin and floating compact zhuyin nine at 2, 2.75 and 3.5 px a dp, from their
        // height down to 55% a px at a time: every text and icon at most about 2% smaller or larger a px (an
        // icon's whole px: a px of it, 2.2% of a 46 px one), and every shortcut and ⌫ icon within its face's
        // arcs (060-render-p2 S4e); some against them (within the half px the yields keep off the face's edges,
        // the most a pixel's rounding moves them), or the arcs were never in play
        val boards = listOf("qwerty", "k14", "k18", "zhuyin", "handwriting").map { it to false } + ("zhuyin-nine" to true)
        var against = 0
        for (d in listOf(1.5, 2.0, 2.75, 3.5)) for ((board, compact) in boards) against += sweepSmoothly(d, board, Forms.floating.copy(compact = compact))
        assertTrue(against >= 10, "against the arcs: $against")
    }

    @Test
    fun aCompactBoardsClassesShrinkSmoothlyAndItsIconsKeepWithinTheArcs() {
        // the same for compact and small compact qwerty, k14 and k18; where a key's face is shorter than its
        // text (small compact k18 near 55%: 21 px of face, 36 px of text) the text gives a px for each px of
        // face: up to 3.5% a px there, only ever smaller (060-render-p2 S4e)
        val small = Forms.compact.copy(width = 990.0 * 2.625 / 2.75, height = 407.0 * 2.625 / 2.75)
        for (d in listOf(1.5, 2.0, 2.75, 3.5)) for (form in listOf(Forms.compact, small)) for (board in listOf("qwerty", "k14", "k18")) sweepSmoothly(d, board, form)
    }

    // the icons against the arcs on the way
    private fun sweepSmoothly(d: Double, board: String, form: Env): Int {
        val k = d / Forms.DENSITY
        val top = form.height * k
        var last: Map<String, Pair<Double, Boolean>>? = null
        var h = top
        var against = 0
        while (h >= top * 0.55) {
            val sc = Forms.scene(board, Forms.env(board, form.copy(width = form.width * k, height = h, density = d)))
            val at = "at $d, ${form.width} ${if (form.compact) "compact " else ""}$board $h px"
            against += sc.keys.sumOf { iconsWithinArcs(it, at) }
            val sizes = sc.keys.flatMap { key -> key.ops(false).mapNotNull { op -> sized(key, op) } }.toMap()
            last?.let { was -> sizes.forEach { (id, v) -> was[id]?.let { o -> smallStep(id, o.first, v, d, at) } } }
            last = sizes
            h -= 1.0
        }
        return against
    }

    @Test
    fun aTablesRootsInLinesKeepTheirSymbolTheClasssSize() {
        // floating qwerty at 0.7 of its height, 五笔's roots over the letters (g's in two lines: it yields not),
        // h's symbol a wide one that needs the symbols smaller: g's symbol as much smaller as j's
        // (060-render-p2 S4e M2)
        val e0 = Forms.env("qwerty", Forms.floating.copy(height = Forms.floating.height * 0.7))
        val e = e0.copy(
            input = e0.input.copy(ime = "table", table = "wbx", roots = true),
            texts = e0.texts.copy(
                alts = Fixtures.qwertyAlts + ("h" to "WW"),
                other = Fixtures.qwertyAlts.keys.associate { k -> "root:$k" to k } + mapOf("root:g" to "王一\n五戋"),
            ),
        )
        val s = Forms.scene("qwerty", e)
        val g = texts(s, "g").last { it.text == Fixtures.qwertyAlts.getValue("g") }.size
        val j = texts(s, "j").last { it.text == Fixtures.qwertyAlts.getValue("j") }.size
        val full = texts(Forms.scene("qwerty", Forms.env("qwerty", Forms.floating)), "j").last().size
        assertTrue(j < full - 0.1, "the symbols smaller: $j of $full")
        assertEquals(j, g, 1e-9, "g's as j's")
    }

    @Test
    fun aCompactZhuyinsIconsKeepWithinTheArcsHalfAPxAtATime() {
        // compact and small compact zhuyin and zhuyin nine at every density, from their height down to 55%
        // half a px at a time (between whole px of keyboard too): every icon within the arcs (060-render-p2 S4e)
        val small = Forms.compact.copy(width = 990.0 * 2.625 / 2.75, height = 407.0 * 2.625 / 2.75)
        for (d in listOf(1.5, 2.0, 2.75, 3.5)) for (form in listOf(Forms.compact, small)) for (board in listOf("zhuyin", "zhuyin-nine")) halfPxWithinArcs(d, board, form)
    }

    private fun halfPxWithinArcs(d: Double, board: String, form: Env) {
        val k = d / Forms.DENSITY
        val top = form.height * k
        generateSequence(top) { it - 0.5 }.takeWhile { it >= top * 0.55 }.forEach { h ->
            val e = Forms.env(board, form.copy(width = form.width * k, height = h, density = d))
            Forms.scene(board, e).keys.forEach { iconsWithinArcs(it, "at $d, ${form.width} $board $h px") }
        }
    }

    @Test
    fun aBackspacesIconKeepsWithinTheArcsBetweenWholePx() {
        // compact and small compact zhuyin where the rv4e sweep had ⌫'s and the space's icons out of the arcs
        // without onto's last within (between whole px of keyboard): within them (060-render-p2 S4e M1)
        val small = Forms.compact.copy(width = 990.0 * 2.625 / 2.75, height = 407.0 * 2.625 / 2.75)
        for (form in listOf(Forms.compact, small)) for ((d, h) in listOf(3.5 to 409.2, 3.5 to 414.2, 2.75 to 322.8, 2.75 to 325.8, 2.75 to 326.8)) {
            val k = d / Forms.DENSITY
            for (board in listOf("zhuyin", "zhuyin-nine", "handwriting")) {
                val e = Forms.env(board, form.copy(width = form.width * k, height = h, density = d))
                Forms.scene(board, e).keys.forEach { iconsWithinArcs(it, "at $d, ${form.width} $board $h px") }
            }
        }
    }

    // a text's size (its scale too), an icon's width, by key and what it is; whether it is the key's
    // glyph (its largest text) and taller than the key's face
    private fun sized(key: KeyNode, op: Op): Pair<String, Pair<Double, Boolean>>? {
        val face = (key.shape as? Shape.Rect)?.box?.height ?: Double.MAX_VALUE
        val glyph = key.ops(false).filterIsInstance<Op.Text>().maxOfOrNull { it.size }
        return when (op) {
            is Op.Text -> "${key.id} T ${op.text}" to (op.size * op.scaleX to (op.size == glyph && face < op.size))
            is Op.Icon -> "${key.id} I ${op.name}" to (op.box.width to false)
            else -> null
        }
    }

    // a px of keyboard, at [d] px a dp: at most 0.04 / d from [o] to [v] (2% at 2 px a dp: the less a px is
    // of a dp, the less it moves), or a px for an icon (whole px); a glyph taller than its face more, up to
    // 0.07 / d, only smaller (the face's px it gives; growing, as a key fades out of its class's need, as any)
    private fun smallStep(id: String, o: Double, v: Pair<Double, Boolean>, d: Double, at: String) {
        val (now, tall) = v
        val most = 0.04 / d
        if (tall && o - now > most * o) {
            assertTrue(o - now <= 0.07 / d * o + 1e-6, "$id $o to $now, taller than its face, $at")
            return
        }
        val slack = if (" I " in id) 1.0 + 1e-6 else 0.0
        assertTrue(abs(now - o) <= maxOf(most * o, slack) + 1e-6, "$id $o to $now $at")
    }

    // [key]'s shortcut and ⌫ icons' box corners within its face's rounded ones (less the border); how many against them
    private fun iconsWithinArcs(key: KeyNode, at: String): Int {
        val shape = key.shape as? Shape.Rect ?: return 0
        val f = shape.box
        val line = key.ops(false).filterIsInstance<Op.Stroke>().firstOrNull()?.width ?: 0.0
        var against = 0
        for (icon in key.ops(false).filterIsInstance<Op.Icon>().filter { it.name in movingIcons }) {
            val b = icon.box
            val corners = listOf(
                Triple(shape.radii[0], f.left + shape.radii[0] - b.left, f.top + shape.radii[0] - b.top),
                Triple(shape.radii[1], b.right - (f.right - shape.radii[1]), f.top + shape.radii[1] - b.top),
                Triple(shape.radii[2], b.right - (f.right - shape.radii[2]), b.bottom - (f.bottom - shape.radii[2])),
                Triple(shape.radii[3], f.left + shape.radii[3] - b.left, b.bottom - (f.bottom - shape.radii[3])),
            )
            for ((r, x, y) in corners.filter { (r, x, y) -> r > line && x > 0.0 && y > 0.0 }) {
                val out = sqrt(x * x + y * y) - (r - line)
                assertTrue(out <= 0.01, "within the arc $at, ${key.id}'s ${icon.name}: $b in $f, corners $r: $out px out")
                if (out > -0.6) against++
            }
        }
        return against
    }

    @Test
    fun aLettersSymbolGoesUpOrAsideOnceAsTheBoardGetsShorter() {
        // floating qwerty at 2 px a dp a px shorter at a time, v's face 63 px down to 47 (51 and 52 by turns,
        // the rows' rounding): '?' goes up out of v's way, then aside, and never back; the way is v's at its
        // size, not the letters' as they get smaller
        val d = 2.0
        val k = d / Forms.DENSITY
        val heights = mutableSetOf<Double>()
        var last: Double? = null
        var turns = 0
        var h = 360.8
        while (h >= 298.8) {
            val s = Forms.scene("qwerty", Forms.env("qwerty", Forms.floating.copy(width = Forms.floating.width * k, height = h, density = d)))
            val f = face(s, "v")
            heights += f.height
            val x = texts(s, "v").first { it.text == "?" }.x - f.left
            last?.let { if (abs(x - it) > 1.5) turns++ }
            last = x
            h -= 1.0
        }
        assertTrue(heights.min() <= 48.0 && heights.max() >= 54.0, "v's face 48 to 54 px: $heights")
        assertEquals(1, turns, "'?' up, then aside, once")
    }

    @Test
    fun theLettersOfAShortFloatingBoardAreSmallerOnlyOnceTheirIconsHaveEatenTheirInsets() {
        // a floating board three quarters its height: the top left icons eat their insets before every letter is
        // smaller (0.942 to 0.953 of the letters with no shortcuts drawn at 2 to 3.5 px a dp; 0.911 to 0.922
        // were the icons kept to their rooms)
        for (d in listOf(2.0, 2.625, 2.75, 3.0, 3.5)) {
            val k = d / Forms.DENSITY
            val form = Forms.floating.copy(width = Forms.floating.width * k, height = Forms.floating.height * k * 0.75, density = d, compact = true)
            fun size(e: Env) = texts(Forms.scene("qwerty", Forms.env("qwerty", e)), "q").maxOf { it.size }
            val ratio = size(form) / size(form.copy(marks = form.marks.copy(charShortcuts = false)))
            assertTrue(ratio > 0.935 && ratio < 1.0, "at $d the letters $ratio of their size")
        }
    }

    @Test
    fun aBorderKeepsTheIconsOffIt() {
        // the small phone's ⌫ as above, its face square, a 1 dp border (all of the inset): the icons eat none of
        // it, drawn smaller rather than on the line (45.5 px of face), their size taller (49.5); at 20% (43.5) the
        // down one left out
        val d = 2.75
        fun theme(border: String) = ThemeReader.theme(
            testData("material-light.xml").replace("</theme>", "<style select=\"key\" radius=\"0dp\" border=\"$border\"/>\n</theme>"),
        ).ok()
        fun scene(height: Double) = Forms.scene("zhuyin", Forms.env("zhuyin", Fixtures.compact.copy(width = 990.0, height = height, density = d)), theme("1dp #ff112233"))
        assertEquals(setOf("backspace", "delete-sweep"), icons(scene(407.0), "backspace").map { it.name }.toSet(), "at 20%")
        for (height in listOf(420.0, 440.0)) {
            val s = scene(height)
            val f = face(s, "backspace")
            val icons = icons(s, "backspace").associate { it.name to it.box }
            assertEquals(setOf("backspace", "delete-sweep", "restore"), icons.keys, "a 1 dp border, ${f.height}")
            assertTrue(icons.getValue("delete-sweep").top >= f.top + 1 * d - 0.01, "up off the border: ${icons.getValue("delete-sweep")} $f")
            assertTrue(icons.getValue("restore").bottom <= f.bottom - 1 * d + 0.01, "down off the border: ${icons.getValue("restore")} $f")
        }
    }

    @Test
    fun aFloatingBoardsBackspaceKeepsBothItsIconsTheirClassOneSize() {
        // every floating board at every density, its height: ⌫'s two left icons, their size, meet its icon where
        // it is; it goes aside (its class no smaller than the cap) and both are drawn, the shortcut icons one
        // size, no larger than theirs (060-render-p2 S4e)
        for (d in listOf(2.0, 2.625, 2.75, 3.5)) for (board in listOf("qwerty", "k14", "k18", "zhuyin-nine", "number", "symbols")) {
            val k = d / Forms.DENSITY
            val e = Forms.env(board, Forms.floating.copy(width = Forms.floating.width * k, height = Forms.floating.height * k, density = d))
            val s = Forms.scene(board, e)
            val at = "$board at $d"
            val icons = icons(s, "backspace").associate { it.name to it.box }
            assertEquals(setOf("backspace", "delete-sweep", "restore"), icons.keys, at)
            val w = icons.getValue("backspace").width
            assertTrue(w >= 24 * d * (1 - KeyMarkYield.MAX_CLASS_SHRINK) - 0.01 && w <= 24 * d + 0.01, "the icon within its class's cap $at: $w")
            assertTrue(icons.getValue("backspace").left > icons.getValue("restore").right, "the icon clear of them $at")
            assertEquals(icons.getValue("delete-sweep").width, icons.getValue("restore").width, at)
            assertTrue(icons.getValue("delete-sweep").width <= floor(Content.SHORTCUT_ICON_DP * d + 0.5) + 0.01, "no larger $at")
        }
    }

    @Test
    fun aBackspacesIconsMoveWithItsFaceAPxAtATimeNeverAWholeInsetAtOnce() {
        // the keyboard a px shorter at a time, from where the icons sit as they were to where one is left out:
        // each kept icon and the glyph move no more than that px (the insets eaten a share at a time, worked
        // out on the edges before they are put on whole pixels)
        for (d in listOf(2.0, 2.625, 2.75, 3.0, 3.5)) {
            var last: Map<String, Box>? = null
            var px = 0
            while (px < 40) {
                val h = 150.0 * d - px
                val e = Forms.env("zhuyin", Fixtures.compact.copy(width = 360.0 * d, height = h, density = d))
                val s = Forms.scene("zhuyin", e)
                val f = face(s, "backspace")
                val now = icons(s, "backspace").associate { it.name to it.box.moved(-f.left, -f.top) }
                val was = last
                if (was != null && was.keys == now.keys) {
                    for ((name, b) in now) {
                        val o = was.getValue(name)
                        val moved = maxOf(abs(b.left - o.left), abs(b.top - o.top), abs(b.right - o.right), abs(b.bottom - o.bottom))
                        assertTrue(moved <= 1.0 + 0.01, "at $d, the face ${f.height} px, $name moved $moved for a px of keyboard: $o to $b")
                    }
                }
                last = now
                px++
            }
        }
    }

    @Test
    fun marksFollowTheSettings() {
        val off = env.copy(marks = MarkSettings(symbols = false, charShortcuts = false, upOn = false, downOn = false))
        val s = Fixtures.scene(off)
        assertEquals(listOf("y"), texts(s, "y").map { it.text })
        assertEquals(listOf("backspace"), icons(s, "backspace").map { it.name })
        val text = env.copy(marks = MarkSettings(shortcuts = mapOf("full/a" to MarkContent.Text("全选全选全选"))))
        val a = texts(Fixtures.scene(text), "a").first { it.text == "全选全选全选" }
        assertTrue(a.scaleX < 1.0, "squeezed to 45% of the key")
        near(9 * d, a.size)
        val bottom = env.copy(marks = MarkSettings(symbolAt = KeyMarks.At.Bottom))
        val q = Fixtures.scene(bottom)
        val one = texts(q, "q").first { it.text == "1" }
        val label = texts(q, "q").first { it.text == "q" }
        assertTrue(one.baseline > label.baseline, "the symbol below the label")
        near(face(q, "q").bottom - 2 * d - 0.3 * Content.SYMBOL_DP * d, one.baseline, "2 dp up from the face's bottom")
    }

    @Test
    fun iconsBadgesAndHiddenLabels() {
        val s = Fixtures.scene(env)
        val shift = icons(s, "shift").single()
        near(24 * d, shift.box.width)
        near(face(s, "shift").centerX, shift.box.centerX)
        val voice = Fixtures.scene(env.copy(input = Input(holdVoice = true)))
        val badge = icons(voice, "space").first { it.name == "voice" }
        val label = texts(voice, "space").single()
        near(15 * d, badge.box.width)
        near(badge.box.right + 3 * d, label.x, "3 dp before the label")
        val t = ThemeReader.theme(testData("material-light.xml").replace("</theme>", "<style select=\"*/space label\" hidden=\"true\"/>\n</theme>")).ok()
        val hidden = Fixtures.scene(env, theme = t)
        assertTrue(texts(hidden, "space").isEmpty())
    }

    @Test
    fun theLabelAboveASymbolBelowCentresToTheSymbolsExtent() {
        // M2: the symbol's TextView stood by the font's extent (1.05 of its size above the baseline), not its ink
        // no shortcuts: nothing meets, so the letters are their size
        val s = Fixtures.scene(env.copy(marks = MarkSettings(symbolAt = KeyMarks.At.Bottom, charShortcuts = false)))
        val f = face(s, "q")
        val one = texts(s, "q").first { it.text == "1" }
        val symbolTop = one.baseline - 1.05 * one.size
        val q = texts(s, "q").first { it.text == "q" }
        val cy = (f.top + symbolTop) / 2
        near(cy - 1.35 * q.size / 2 + 1.05 * q.size, q.baseline, "the label's extent centred between the face's top and the symbol's")
    }

    @Test
    fun aBadgeStaysWhereTheLabelIsHiddenOrThereIsNone() {
        // M6: a theme hiding the label keeps the badge and the shortcuts (rules B §5), the badge where it was
        val voice = env.copy(input = Input(holdVoice = true))
        val shown = icons(Fixtures.scene(voice), "space").first { it.name == "voice" }
        val t = ThemeReader.theme(testData("material-light.xml").replace("</theme>", "<style select=\"*/space label\" hidden=\"true\"/>\n</theme>")).ok()
        val hidden = Fixtures.scene(voice, theme = t)
        assertTrue(texts(hidden, "space").isEmpty())
        assertEquals(shown.box, icons(hidden, "space").first { it.name == "voice" }.box)
        assertEquals("cursor-move", icons(hidden, "space").last().name, "the shortcut still drawn")
        // an input method with no name: the badge alone, its 3 dp after it still counted
        val nameless = Fixtures.scene(voice.copy(texts = voice.texts.copy(imeName = "")))
        val badge = icons(nameless, "space").first { it.name == "voice" }
        near(face(nameless, "space").centerX - 1.5 * d, badge.box.centerX, "15 dp and 3 dp centred")
    }

    @Test
    fun anIconAloneIsDrawnAtItsOwnSize() {
        // L2: ImageKeyView's wrap_content, the drawable's size
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"10\">" +
                "<row><key id=\"a\" icon=\"wide\" a11y=\"a\" width=\"5\"/><key id=\"b\" icon=\"lock\" a11y=\"b\" width=\"5\"/></row></keyboard>",
        ).ok()
        val sizes = IconSizes { name -> if (name == "wide") 32.0 to 20.0 else 24.0 to 24.0 }
        val s = SceneBuilder(Fixtures.materialLight, env, FakeMeasurer, sizes).build(l).ok()
        val wide = icons(s, "a").single()
        near(32 * d, wide.box.width)
        near(20 * d, wide.box.height)
        near(face(s, "a").centerX, wide.box.centerX)
        near(24 * d, icons(s, "b").single().box.width)
    }

    @Test
    fun anIconWithALabelAboveIt() {
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"10\">" +
                "<row><key id=\"a\" label=\"emoji\" icon=\"emoji\" width=\"10\"/></row></keyboard>",
        ).ok()
        val s = Fixtures.scene(env, l)
        val icon = icons(s, "a").single()
        val label = texts(s, "a").single()
        near(13 * d, icon.box.width)
        assertTrue(icon.box.bottom < label.baseline)
    }

    @Test
    fun textScalesCompactAndFloatingRuleB6() {
        near(23 * d * 0.9, texts(Fixtures.scene(Fixtures.compact), "q").first { it.text == "q" }.size)
        near(23 * d * 0.85, texts(Fixtures.scene(Fixtures.floating), "q").first { it.text == "q" }.size)
        near(Content.SYMBOL_DP * d * 0.85, texts(Fixtures.scene(Fixtures.floating), "q").first { it.text == "1" }.size)
    }

    @Test
    fun crowdedLettersShrinkTogetherRuleB7Yield() {
        // shorter and shorter keys: every letter one size at each height (060-render-p2 S4e). 540 px across: the
        // fixture's letters wider than their room too (rule B §8a), past the class's cap: the class at its cap,
        // the rest each shrunk alone alike, so one size still, the same at every height (nothing left for the
        // height to take); so short its symbols meet their letters, those symbols left out
        val all = mutableSetOf<Double>()
        for (h in 240..400 step 8) {
            val s = Fixtures.scene(env.copy(width = 540.0, height = h.toDouble()))
            val letters = s.keys.filter { it.kind == "letter" && it.role == null }
            val sizes = letters.map { k -> k.ops(false).filterIsInstance<Op.Text>().first { it.text == k.id }.size }.toSet()
            // one class, one scale: the same size to the last bit
            assertEquals(1, sizes.size, "at $h: $sizes")
            assertTrue(sizes.single() < 23 * d * (1 - KeyMarkYield.MAX_CLASS_SHRINK), "past the class's cap at $h")
            all += sizes.single()
            if (h == 240) assertTrue(letters.any { k -> k.ops(false).filterIsInstance<Op.Text>().size == 1 }, "a symbol left out")
        }
        assertEquals(1, all.size, "every height: $all")
    }

    // a table's roots over the letters (TextKeyboard): `when=roots`, `{root:x}`; the letter in the corner
    @Test
    fun aTablesRootsInLinesInOneLinesHeightShrunkToTheFace() {
        val roots = env.copy(
            input = env.input.copy(ime = "table", table = "wbx", roots = true),
            texts = env.texts.copy(
                alts = Fixtures.qwertyAlts.mapValues { (k, _) -> k },
                other = mapOf("root:q" to "金", "root:w" to "人亻八\n癶", "root:e" to "月彡乃用"),
            ),
        )
        val s = Fixtures.scene(roots)
        val size = 23 * d
        // a name: one character, as a letter is drawn, the letter in its corner
        near(size, texts(s, "q").first { it.text == "金" }.size)
        assertTrue(texts(s, "q").any { it.text == "q" })
        // in two lines, each at 0.55 of the size, one under the other, centred across; three wide
        // characters at that wider than the face less 6 dp a side: the two shrunk into it alike
        val w = texts(s, "w").filter { it.text != "w" }
        assertEquals(listOf("人亻八", "癶"), w.map { it.text })
        val f = face(s, "w")
        val room = f.width - 2 * Content.ROOTS_INSET_DP * d
        near(minOf(size * Content.ROOTS_LINES_SCALE, room / 3), w[0].size)
        near(w[0].size, w[1].size)
        near(1.35 * w[0].size, w[1].baseline - w[0].baseline, "a line's height")
        near(f.centerX, w[0].x + 3 * w[0].size / 2, "centred")
        near(f.centerX, w[1].x + w[1].size / 2, "centred")
        // four roots on a line of the full size are wider than the face less 6 dp a side: shrunk into
        // it (and with the letters then, out of their corners' way: one line's ink is measured)
        val e = texts(s, "e").single { it.text == "月彡乃用" }
        assertTrue(4 * e.size <= room + 0.01, "shrunk to the face: ${4 * e.size}")
        assertTrue(4 * e.size > room * 0.7, "not past the letters' yield: ${4 * e.size}")
        // no roots: the letters as ever
        assertTrue(texts(Fixtures.scene(env), "w").any { it.text == "w" })
    }

    @Test
    fun codePoints() {
        assertEquals(1, Content.codePoints("😀"))
        assertEquals(2, Content.codePoints("ab"))
        assertEquals(1, Content.codePoints("\uD83D"))
    }
    // 060-render-p2 S4f: a phone upright 393 dp at 2.75, "@" as wide as Roboto's at the bottom left and 全选 at
    // the bottom: the yields keep them apart with the icon where it is placed, drawn on whole pixels ([Content]'s
    // laidOut) almost a px further left, back onto the "@" they had moved left of it
    @Test
    fun aKeysMarksStayApartWhereItsIconIsLaidOutOnWholePixels() {
        val wide = object : TextMeasurer {
            override fun measure(text: String, font: String, weight: Int, size: Double) = FakeMeasurer.measure(text, font, weight, size).let { m ->
                if (text == "@") m.copy(ink = Box(m.ink.left, m.ink.top, m.ink.left + 0.82 * size, m.ink.bottom), advance = 0.9 * size) else m
            }
        }
        val d = 2.75
        val marks = MarkSettings(symbolAt = KeyMarks.At.BottomLeft, shortcutAt = KeyMarks.At.Bottom)
        for (share in listOf(0.30, 0.32, 0.34, 0.36)) {
            val env = Forms.env("qwerty", Forms.compact.copy(compact = false, width = 393 * d, height = 851 * d * share, density = d, marks = marks))
            val key = SceneBuilder(Fixtures.materialLight, env, wide).build(Forms.layout("qwerty"), Forms.fragments).ok().keys.first { it.id == "a" }
            val inks = key.ops(false).mapNotNull { op ->
                when (op) {
                    is Op.Icon -> op.box
                    is Op.Text -> if (op.text == "@") wide.measure(op.text, op.font, op.weight, op.size).ink.let {
                        Box(it.left * op.scaleX + op.x, it.top + op.baseline, it.right * op.scaleX + op.x, it.bottom + op.baseline)
                    } else null
                    else -> null
                }
            }
            assertEquals(2, inks.size, "$share: both drawn")
            assertTrue(!inks[0].toInk().meets(inks[1].toInk(), 0.0), "$share: ${inks[0]} meets ${inks[1]}")
        }
    }
}
