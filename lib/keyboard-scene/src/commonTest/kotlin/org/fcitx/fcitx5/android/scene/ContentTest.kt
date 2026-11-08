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
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 060-render-v2 §6 step 5: labels, icons and marks where theme 3.0's views put them (rules B §5–§7)
class ContentTest {
    private val env = Fixtures.portrait
    private val d = Fixtures.DENSITY

    private fun near(e: Double, a: Double, what: String = "") = assertTrue(abs(e - a) < 0.01, "$what: expected $e, was $a")

    private fun ops(s: Scene, id: String, pressed: Boolean = false) = s.keys.first { it.id == id }.ops(pressed)

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
        near(face(s, "space").centerX - kotlin.math.ceil(2 * size) / 2, label.x)
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
        near(kotlin.math.floor(f.left + 3 * d), redo.box.left)
        near(kotlin.math.floor(f.top + 2 * d), redo.box.top)
        near(kotlin.math.floor(11 * d + 0.5), redo.box.width)
        val backspace = icons(s, "backspace").map { it.name }
        assertEquals(listOf("backspace", "delete-sweep", "restore").sorted(), backspace.sorted())
        val down = icons(s, "backspace").first { it.name == "restore" }
        assertTrue(kotlin.math.abs(face(s, "backspace").bottom - 2 * d - down.box.bottom) < 1.0, "down's at the bottom, within its pixel")
        assertEquals("cursor-move", icons(s, "space").single().name)
    }

    @Test
    fun aShortBackspaceKeepsBothItsIconsOneOverTheOther() {
        // 注音大千 on a 360 dp phone at 440 dpi, compact at 20%: a ⌫ face 43.5 px tall. Its 8 dp icons 1 dp
        // in are 38 px apart, at their least (7 dp) 38.5 tall together: they eat into their insets, half a px
        // between them, rather than one is left out for half a px
        val d = 2.75
        val small = Forms.env("zhuyin", Fixtures.compact.copy(width = 990.0, height = 407.0, density = d))
        val s = Forms.scene("zhuyin", small)
        val f = face(s, "backspace")
        near(43.5, f.height, "the face")
        val icons = icons(s, "backspace").associate { it.name to it.box }
        assertEquals(setOf("backspace", "delete-sweep", "restore"), icons.keys)
        val glyph = icons.getValue("backspace")
        val up = icons.getValue("delete-sweep")
        val down = icons.getValue("restore")
        assertTrue(up.right < glyph.left && down.right < glyph.left, "both left of ⌫: $up $down $glyph")
        assertTrue(up.bottom <= down.top + 0.01, "one over the other: $up $down")
        near(7 * d, up.height, "up at its least")
        near(7 * d, down.height, "down at its least")
        // into their insets the least: half a px between them, a quarter each, still 0.75 dp and more in
        near(f.top + 1 * d - 0.25, up.top, "up a quarter px up")
        near(f.bottom - 1 * d + 0.25, down.bottom, "down a quarter px down")
    }

    @Test
    fun twoIconsOneOverTheOtherAreLeftOutOnlyWhereTheirInsetsEatenStillDoNotPartThem() {
        // whatever the density and the keyboard's size, a ⌫'s two icons are both drawn wherever they fit at
        // their least (7 dp each) with their insets eaten to a quarter dp; one left out only where not
        val floor = 0.25
        var squeezed = 0
        var left = 0
        val grid = listOf(2.0, 2.625, 2.75, 3.0, 3.5).flatMap { d ->
            listOf(320.0, 360.0, 393.0, 411.0, 600.0).flatMap { w -> listOf(130.0, 140.0, 141.0, 142.0, 143.0, 145.0, 147.0, 148.0, 160.0, 200.0).map { Triple(d, w, it) } }
        }
        for ((d, wDp, hDp) in grid) {
            val e = Forms.env("zhuyin", Fixtures.compact.copy(width = wDp * d, height = hDp * d, density = d))
            val s = Forms.scene("zhuyin", e)
            val f = face(s, "backspace")
            val content = f.height
            val fits = content - 2 * floor * d >= 2 * 7 * d - 0.01
            val names = icons(s, "backspace").map { it.name }.toSet()
            val at = "at $d, $wDp × $hDp dp, a face ${f.height} px"
            if (fits) {
                if (content - 2 * Content.SHORTCUT_EDGE_COMPACT_DP * d < 2 * 7 * d - 0.01) squeezed++
                assertEquals(setOf("backspace", "delete-sweep", "restore"), names, at)
                val up = icons(s, "backspace").first { it.name == "delete-sweep" }.box
                val down = icons(s, "backspace").first { it.name == "restore" }.box
                assertTrue(up.bottom <= down.top + 0.01, "apart $at: $up $down")
                assertTrue(up.top >= f.top + floor * d - 0.01, "up in its face $at: $up")
                assertTrue(down.bottom <= f.bottom - floor * d + 0.01, "down in its face $at: $down")
            } else {
                left++
                assertEquals(setOf("backspace", "delete-sweep"), names, at)
            }
        }
        // the sweep met all three: kept as they were, kept by eating their insets, one left out
        assertTrue(squeezed >= 10 && left >= 10, "squeezed $squeezed, left out $left")
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
        val s = Fixtures.scene(env.copy(marks = MarkSettings(symbolAt = KeyMarks.At.Bottom)))
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
        // a key so short its symbol meets its letter: every letter the same smaller size
        val e: Env = env.copy(width = 540.0, height = 240.0)
        val s = Fixtures.scene(e)
        val sizes = s.keys.filter { it.kind == "letter" && it.role == null }.map { k ->
            k.ops(false).filterIsInstance<Op.Text>().first { it.text == k.id }.size
        }.toSet()
        assertEquals(1, sizes.size, "$sizes")
        assertTrue(sizes.single() < 23 * d, "${sizes.single()}")
        assertTrue(sizes.single() >= 23 * d * 0.75 - 0.01, "a quarter smaller at most")
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
}
