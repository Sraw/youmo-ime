/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Content
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.engine.MarkSettings
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

    private fun ops(s: Scene, id: String, state: KeyState = KeyState.Normal) = s.keys.first { it.id == id }.faces.getValue(state)

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
        near(f.left + 3 * d, redo.box.left)
        near(f.top + 2 * d, redo.box.top)
        near(11 * d, redo.box.width)
        val backspace = icons(s, "backspace").map { it.name }
        assertEquals(listOf("backspace", "delete-sweep", "restore").sorted(), backspace.sorted())
        val down = icons(s, "backspace").first { it.name == "restore" }
        near(face(s, "backspace").bottom - 2 * d, down.box.bottom, "down's at the bottom")
        assertEquals("cursor-move", icons(s, "space").single().name)
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
            k.faces.getValue(KeyState.Normal).filterIsInstance<Op.Text>().first { it.text == k.id }.size
        }.toSet()
        assertEquals(1, sizes.size, "$sizes")
        assertTrue(sizes.single() < 23 * d, "${sizes.single()}")
        assertTrue(sizes.single() >= 23 * d * 0.75 - 0.01, "a quarter smaller at most")
    }

    @Test
    fun codePoints() {
        assertEquals(1, Content.codePoints("😀"))
        assertEquals(2, Content.codePoints("ab"))
        assertEquals(1, Content.codePoints("\uD83D"))
    }
}
