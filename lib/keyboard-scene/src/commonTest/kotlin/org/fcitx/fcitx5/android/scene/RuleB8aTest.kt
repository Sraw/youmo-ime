/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.near
import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.Fixtures.texts
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.engine.TextMetrics
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.rules.KeyMarkYield
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Rules B §8a: a label wider than its key's room shrinks its class, then alone; ink standing out moved back in. */
class RuleB8aTest {
    private val env = Fixtures.portrait
    private val d = Fixtures.DENSITY

    private fun face(s: Scene, id: String) = (s.keys.first { it.id == id }.shape as Shape.Rect).box

    // a label a little wider than its room shrinks its class all, the most its widest needs
    @Test
    fun aLabelALittleWiderThanItsRoomShrinksItsClassAllRuleB8a() {
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"10\">" +
                "<row><key id=\"a\" label=\"a\" width=\"1\"/><key id=\"w\" label=\"ab\" width=\"1\"/></row></keyboard>",
        ).ok()
        val ink = 1.1 * 23 * d
        fun room(s: Scene) = face(s, "w").let { Box(it.left + 2 * d, it.top, it.right - 2 * d, it.bottom) }
        val s = (700..1080 step 2).asSequence().map { Fixtures.scene(Fixtures.portrait.copy(width = it.toDouble()), l) }
            .first { room(it).width / ink in 0.95..0.98 }
        val w = texts(s, "w").single()
        assertTrue(w.size * 1.1 <= room(s).width + 0.5, "within its room: ${w.size * 1.1} in ${room(s).width}")
        val needs = 23 * d * room(s).width / ink
        assertTrue(abs(needs - w.size) < 0.001 * 23 * d, "as much smaller as it needs: expected $needs, was ${w.size}")
        assertEquals(w.size, texts(s, "a").single().size, "its class one size")
    }

    // ink off to one side of its advance: its ink's middle the room's, so the class smaller leaves it within both sides
    @Test
    fun aLabelALittleWiderWithItsInkOffToOneSideEndsWithinItsRoomRuleB8a() {
        val skewed = object : TextMeasurer {
            override fun measure(text: String, font: String, weight: Int, size: Double): TextMetrics {
                val m = FakeMeasurer.measure(text, font, weight, size)
                return TextMetrics(m.advance, Box(m.ink.left + 0.1 * m.advance, m.ink.top, m.ink.right + 0.1 * m.advance, m.ink.bottom), m.top, m.bottom)
            }
        }
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"10\">" +
                "<row><key id=\"a\" label=\"a\" width=\"1\"/><key id=\"w\" label=\"ab\" width=\"1\"/></row></keyboard>",
        ).ok()
        fun room(s: Scene) = face(s, "w").let { Box(it.left + 2 * d, it.top, it.right - 2 * d, it.bottom) }
        val s = (700..1080 step 2).asSequence().map { SceneBuilder(Fixtures.materialLight, Fixtures.portrait.copy(width = it.toDouble()), skewed).build(l).ok() }
            .first { 1.1 * 23 * d / room(it).width in 1.025..1.035 }
        val r = room(s)
        val t = texts(s, "w").single()
        val ink = skewed.measure(t.text, t.font, t.weight, t.size).ink
        assertTrue(t.x + ink.left >= r.left - 0.01, "its left within: ${t.x + ink.left} of ${r.left}")
        assertTrue(t.x + ink.right <= r.right + 0.01, "its right within: ${t.x + ink.right} of ${r.right}")
        assertTrue(t.size < 23 * d, "smaller")
    }

    // a font whose ink grows by steps with its size (hinting): a label shrunk alone measured again at the class's most
    @Test
    fun aLabelWhoseInkGrowsByStepsEndsWithinItsRoomRuleB8a() {
        val steps = object : TextMeasurer {
            override fun measure(text: String, font: String, weight: Int, size: Double): TextMetrics {
                val m = FakeMeasurer.measure(text, font, weight, size)
                val g = FakeMeasurer.measure(text, font, weight, ceil(size / 2) * 2).ink
                return TextMetrics(m.advance, Box(floor(m.ink.left), m.ink.top, floor(m.ink.left) + ceil(g.right - g.left), m.ink.bottom), m.top, m.bottom)
            }
        }
        val out = mutableListOf<String>()
        for (label in listOf("ab", "abcd", "abcdefghij")) {
            val l = LayoutReader.layout(
                "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"10\">" +
                    "<row><key id=\"w\" label=\"$label\" width=\"1\"/><key id=\"x\" label=\"x\" width=\"1\"/></row></keyboard>",
            ).ok()
            for (w in 600..1080 step 3) {
                val s = SceneBuilder(Fixtures.materialLight, Fixtures.portrait.copy(width = w.toDouble()), steps).build(l).ok()
                val f = face(s, "w")
                val t = texts(s, "w").single()
                val i = steps.measure(t.text, t.font, t.weight, t.size).ink
                if (t.x + i.left < f.left + 2 * d - 0.5 || t.x + i.right > f.right - 2 * d + 0.5) out += "$label at $w: ${t.x + i.left}..${t.x + i.right}"
            }
        }
        assertEquals(emptyList<String>(), out)
    }

    // past what its class gives: the class that much smaller, the label shrunk alone the rest
    @Test
    fun aLabelWiderThanItsRoomShrunkAloneToFitRuleB8a() {
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"10\">" +
                "<row><key id=\"a\" label=\"a\" width=\"1\"/><key id=\"w\" label=\"abcdefghij\" width=\"1\"/></row></keyboard>",
        ).ok()
        val s = Fixtures.scene(Fixtures.portrait, l)
        near(23 * d * (1 - KeyMarkYield.MAX_CLASS_SHRINK), texts(s, "a").single().size, "the class at its cap")
        val room = face(s, "w").let { Box(it.left + 2 * d, it.top, it.right - 2 * d, it.bottom) }
        val w = texts(s, "w").single()
        near(room.width / 5.9, w.size, "shrunk to the room")
        near(room.left, w.x + 0.05 * w.size, "its ink's left the room's")
        near(1.0, w.scaleX, "both ways alike")
    }

    // a label that fits across but whose ink stands out (a fullwidth bracket's bearing) moved in, its size kept
    @Test
    fun aLabelWhoseInkStandsOutOfItsRoomMovedBackIntoItRuleB8a() {
        val skewed = object : TextMeasurer {
            override fun measure(text: String, font: String, weight: Int, size: Double): TextMetrics {
                val m = FakeMeasurer.measure(text, font, weight, size)
                return TextMetrics(m.advance, Box(m.advance * 0.4, m.ink.top, m.advance * 1.3, m.ink.bottom), m.top, m.bottom)
            }
        }
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"10\">" +
                "<row><key id=\"w\" label=\"ab\" width=\"1\"/></row></keyboard>",
        ).ok()
        val s = SceneBuilder(Fixtures.materialLight, Fixtures.portrait, skewed).build(l).ok()
        val room = face(s, "w").let { Box(it.left + 2 * d, it.top, it.right - 2 * d, it.bottom) }
        val w = texts(s, "w").single()
        val advance = 2 * 0.6 * w.size
        assertTrue(0.9 * advance < room.width, "fits across")
        near(23 * d, w.size, "its size kept")
        assertTrue(room.centerX - advance / 2 + 1.3 * advance > room.right, "stood out")
        near(room.right, w.x + 1.3 * advance, "its ink's right the room's")
    }

    // a letter shrunk alone has its symbol kept apart from it as drawn, never over it
    @Test
    fun aLetterShrunkToItsRoomKeepsApartFromItsSymbolRuleB8a() {
        fun ink(t: Op.Text) = FakeMeasurer.measure(t.text, t.font, t.weight, t.size).ink.let {
            Box(t.x + it.left * t.scaleX, t.baseline + it.top, t.x + it.right * t.scaleX, t.baseline + it.bottom)
        }
        val s = Fixtures.scene(env.copy(width = 540.0, height = 600.0))
        val letters = s.keys.filter { it.kind == "letter" && it.role == null }
        var both = 0
        for (k in letters) {
            val texts = k.ops(false).filterIsInstance<Op.Text>()
            val letter = texts.first { it.text == k.id }
            assertTrue(letter.size < 23 * d * (1 - KeyMarkYield.MAX_CLASS_SHRINK), "${k.id} shrunk past the cap")
            val g = ink(letter)
            for (m in texts - letter) {
                val i = ink(m)
                both++
                assertTrue(i.right <= g.left || i.left >= g.right || i.bottom <= g.top || i.top >= g.bottom, "${k.id}: ${m.text} $i over $g")
            }
        }
        assertTrue(both > 20, "symbols shown: $both")
    }

    // the symbol yields to the glyph as drawn, not as measured before shrinking
    @Test
    fun aLabelShrunkToItsRoomHasTheMarksOfOneThatWideRuleB8a() {
        fun board(attrs: String) = LayoutReader.layout(Fixtures.qwertySource.replace("label=\"{case:q}\"", "label=\"qqqq\"$attrs")).ok()
        // the four q's line as tall at any size, so the two keys' glyphs differ in nothing but how they came to their size
        val tall = FakeMeasurer.measure("qqqq", "system", 400, 23 * d)
        val m = object : TextMeasurer {
            override fun measure(text: String, font: String, weight: Int, size: Double): TextMetrics {
                val f = FakeMeasurer.measure(text, font, weight, size)
                if (text != "qqqq") return f
                return TextMetrics(f.advance, f.ink, tall.top, tall.bottom)
            }
        }
        fun r(v: Double) = kotlin.math.round(v * 1000) / 1000
        fun mark(s: Scene) = texts(s, "q").filter { it.text != "qqqq" }.map { it.copy(x = r(it.x), baseline = r(it.baseline), size = r(it.size)) }
        for (height in listOf(240.0, 300.0, 360.0, 400.0, 500.0)) {
            val low = env.copy(height = height)
            fun scene(l: org.fcitx.fcitx5.android.scene.layout.Layout) = SceneBuilder(Fixtures.materialLight, low, m).build(l).ok()
            val wide = scene(board(""))
            val q = texts(wide, "q").single { it.text == "qqqq" }
            val room = face(wide, "q").let { it.width - 4 * d }
            near(room, 2.3 * q.size, "shrunk to the room at $height")
            val dp = (q.size / d / (1 - KeyMarkYield.MAX_CLASS_SHRINK)).toString().let { it.take(it.indexOf('.') + 10) }
            val asWide = scene(board(" text-size=\"$dp\""))
            near(q.size, texts(asWide, "q").single { it.text == "qqqq" }.size, "as large at $height")
            assertEquals(mark(asWide), mark(wide), "at $height")
            if (height >= 360) assertEquals(1, mark(wide).size, "a symbol at $height")
        }
    }

    // a label whose ink never comes within its room: drawn at the least the search reaches, and warned of
    @Test
    fun aLabelNeverWithinItsRoomIsAWarningRuleB8a() {
        val board = LayoutReader.layout(Fixtures.qwertySource.replace("label=\"{case:q}\"", "label=\"qqqq\"")).ok()
        val m = object : TextMeasurer {
            override fun measure(text: String, font: String, weight: Int, size: Double): TextMetrics {
                val f = FakeMeasurer.measure(text, font, weight, size)
                if (text != "qqqq") return f
                return TextMetrics(env.width, Box(0.0, f.ink.top, env.width, f.ink.bottom), f.top, f.bottom)
            }
        }
        val scene = SceneBuilder(Fixtures.materialLight, env, m).build(board).ok()
        assertTrue(scene.warnings.any { "qqqq" in it.message }, "${scene.warnings}")
        val fine = SceneBuilder(Fixtures.materialLight, env, FakeMeasurer).build(board).ok()
        assertTrue(fine.warnings.none { "qqqq" in it.message }, "${fine.warnings}")
    }
}
