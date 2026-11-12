/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.engine.MarkSettings
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A pixel's rounding never moves a mark more than a pixel nor changes what is kept (S4e): the label a glyph's
 * rounding put 0.08 px out of its room was moved back in (§8a's within), touched a mark kept at exactly the gap,
 * and the marks were placed all again, an icon 3–4 px away (060-render-p2 S7a review #4). The review's cases,
 * the keyboard a hair narrower and wider about each (on the fake font qwerty's jumped at 0.8873 to 0.8874; the
 * 1.4 px of t's symbol at 0.8877 to 0.8878 is a class's size, the scans' known jumps, outside the window).
 */
class MarkJumpTest {

    // what each key draws but its label, by what it is, where on its face
    private fun marks(s: Scene): Map<String, List<Pair<String, Pair<Double, Double>>>> = s.keys.associate { k ->
        val f = k.bounds
        k.id to k.ops(false).mapNotNull {
            when (it) {
                is Op.Icon -> "icon ${it.name}" to (it.box.left - f.left to it.box.top - f.top)
                is Op.Text -> if (it.text == k.label) null else "text ${it.text}" to (it.x - f.left to it.baseline - f.top)
                else -> null
            }
        }.sortedBy { it.first }
    }

    private fun steady(what: String, board: String, at: (Double) -> Env, f: Double) {
        val shots = (-STEPS..STEPS).map { i -> f + i * STEP }.map { it to marks(Forms.scene(board, Forms.env(board, at(it)))) }
        shots.zipWithNext { (fa, a), (fb, b) ->
            for ((key, ma) in a) {
                val mb = b.getValue(key)
                assertEquals(ma.map { it.first }, mb.map { it.first }, "$what $key: the same kept at $fa and $fb")
                ma.zip(mb).forEach { (x, y) ->
                    val moved = maxOf(abs(x.second.first - y.second.first), abs(x.second.second - y.second.second))
                    assertTrue(moved <= 1.0, "$what $key ${x.first}: moved $moved px from $fa to $fb")
                }
            }
        }
    }

    private fun shortcuts(at: KeyMarks.At) = MarkSettings(
        upAt = if (at == KeyMarks.At.TopRight) KeyMarks.At.TopLeft else KeyMarks.At.TopRight,
        downAt = at,
        shortcuts = Fixtures.presetMarks + mapOf("numbers:up" to MarkContent.Text("全选"), "numbers:down" to MarkContent.Icon("undo")),
    )

    @Test
    fun qwertyCompactAtAWidthsHair() {
        for (at in listOf(KeyMarks.At.TopRight, KeyMarks.At.TopLeft)) {
            steady("qwerty compact $at", "qwerty", { f -> Forms.compact.copy(width = 1080 * f, height = 468 * (0.8 + 0.2 * f), marks = shortcuts(at)) }, 0.8874)
        }
    }

    @Test
    fun floatingLandscapeAtAWidthsHair() {
        steady("floating-landscape", "qwerty", { f -> Forms.floatingLandscape.copy(width = Forms.floatingLandscape.width * f) }, 0.9629)
    }

    private companion object {
        const val STEPS = 3
        const val STEP = 0.0001
    }
}
