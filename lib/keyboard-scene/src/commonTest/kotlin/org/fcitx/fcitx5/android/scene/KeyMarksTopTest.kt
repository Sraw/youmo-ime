/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.icons
import org.fcitx.fcitx5.android.scene.Fixtures.near
import org.fcitx.fcitx5.android.scene.Fixtures.ops
import org.fcitx.fcitx5.android.scene.Fixtures.texts
import org.fcitx.fcitx5.android.scene.engine.Content
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 顶部 (0.6.1) drawn as ContentTest draws the other places: apart from it, which is too large for another
class KeyMarksTopTest {
    private val env = Fixtures.portrait
    private val d = Fixtures.DENSITY

    private fun face(s: Scene, id: String) = (s.keys.first { it.id == id }.shape as Shape.Rect).box

    @Test
    fun aSymbolAtTheTopIsTheTopEdgesMiddleTheLabelWhereItWas() {
        // 顶部 (user 2026-11-13): over the letter, its box's top at the face's top as a corner's is; the label not moved
        // (底部 moves it up, under it there is no room otherwise); a shortcut at 顶部 the top edge's middle too
        val corner = Fixtures.scene(env.copy(marks = env.marks.copy(charShortcuts = false)))
        val s = Fixtures.scene(env.copy(marks = env.marks.copy(upAt = KeyMarks.At.Top, charShortcuts = false)))
        val f = face(s, "q")
        val one = texts(s, "q").first { it.text == "1" }
        val q = texts(s, "q").first { it.text == "q" }
        val size = Content.SYMBOL_DP * d
        // its box (FakeMeasurer: 0.6 of the size an advance, bold 1.05 of it) centred across, to a pixel
        assertTrue(abs(f.centerX - (one.x + 0.6 * 1.05 * size / 2)) < 1.0, "centred across: ${one.x} in $f")
        near(texts(corner, "q").first { it.text == "1" }.baseline, one.baseline, "as high as in its corner")
        near(texts(corner, "q").first { it.text == "q" }.baseline, q.baseline, "the label where it was")
        assertTrue(one.baseline < q.baseline - 1.05 * q.size * 0.5, "above the letter")
        val shortcut = Fixtures.scene(env.copy(marks = env.marks.copy(downAt = KeyMarks.At.Top)))
        val all = icons(shortcut, "a").single { it.name == "select-all" }.box
        // laid out as its ImageView was: to the pixel
        assertTrue(abs(face(shortcut, "a").centerX - all.centerX) < 1.0, "the shortcut centred across: $all")
        assertTrue(all.bottom < face(shortcut, "a").centerY, "in the top half")
    }

    @Test
    fun aTextAtTheTopIsKeptBetweenTheCornersOnItsEdge() {
        // review 061-small S6: 「你好」 at 顶部 read on into 右上's 「1」 as 「你好1」; steps down where it does not fit
        // between them, a gap from each (the synthetic measurer's narrow room: down to the sign), whole where the
        // top has no corner mark
        fun q(upAt: KeyMarks.At): Pair<Scene, List<Op>> {
            val marks = env.marks.copy(
                upAt = upAt, downAt = KeyMarks.At.Top,
                shortcuts = Fixtures.qwertyMarks + ("full/q:down" to MarkContent.Text("你好", "你…")), user = setOf("full/q:down"),
            )
            val s = Fixtures.scene(env.copy(marks = marks))
            return s to ops(s, "q")
        }
        val (s, drawn) = q(KeyMarks.At.TopRight)
        val one = drawn.filterIsInstance<Op.Text>().first { it.text == "1" }
        assertTrue(drawn.none { it is Op.Text && it.text == "你好" }, "stepped down: $drawn")
        val sign = drawn.filterIsInstance<Op.Icon>().single { it.name == "short-text" }.box
        assertTrue(sign.right <= one.x - Content.MIDDLE_GAP_DP * d + 0.01, "a gap from the 1: $sign, ${one.x}")
        assertEquals(mapOf(false to MarkFate.Sign), s.keys.first { it.id == "q" }.fates)
        val (alone, whole) = q(KeyMarks.At.BottomRight)
        assertTrue(whole.any { it is Op.Text && it.text == "你好" }, "no corner at the top: whole: $whole")
        assertEquals(mapOf(false to MarkFate.AsSet), alone.keys.first { it.id == "q" }.fates)
    }

    @Test
    fun aTextAtTheTopIsCutToItsFirstCharacterBeforeTheSign() {
        // user 2026-11-14: 1 dp from the corner, so its first character and 「…」 fit where they can (portrait q's
        // 「你…」 beside its 「1」, the sign at 2 dp), the corner's mark where it was; keys a little wider than the
        // fixture's, where the synthetic measurer's 「你…」 fits only 1 dp off
        val wider = env.copy(width = 1120.0)
        fun q(downAt: KeyMarks.At): Pair<Scene, List<Op>> {
            val marks = wider.marks.copy(
                upAt = KeyMarks.At.TopRight, downAt = downAt,
                shortcuts = Fixtures.qwertyMarks + ("full/q:down" to MarkContent.Text("你好", "你…")), user = setOf("full/q:down"),
            )
            val s = Fixtures.scene(wider.copy(marks = marks))
            return s to ops(s, "q")
        }
        val (s, drawn) = q(KeyMarks.At.Top)
        assertEquals(mapOf(false to MarkFate.Shortened("你…")), s.keys.first { it.id == "q" }.fates)
        val one = drawn.filterIsInstance<Op.Text>().first { it.text == "1" }
        val cut = drawn.filterIsInstance<Op.Text>().single { it.text == "你…" }
        assertTrue(drawn.none { it is Op.Icon && it.name == "short-text" }, "no sign: $drawn")
        // its ink's right (the synthetic measurer's: 0.05 of the size in) a gap from the 1's ink
        assertTrue(cut.x + (1.0 + 0.6 - 0.05) * cut.size <= one.x + 0.05 * one.size - Content.MIDDLE_GAP_DP * d + 0.01, "a gap from the 1: $cut, $one")
        val (_, below) = q(KeyMarks.At.BottomRight)
        near(below.filterIsInstance<Op.Text>().first { it.text == "1" }.x, one.x, "the corner's 1 not moved for it")
    }
}
