/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.engine.MarkSettings
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A long first the user set (a 0.5 backup's 「5%葡萄糖注射液100ml」 as a letter's long press) drawn as its corner
 * symbol stays on its own key: none of a key's marks reaches past its face.
 */
class LongMarksStayOnTheirKeyTest {

    private val long = "5%葡萄糖注射液100ml"

    private fun outside(at: KeyMarks.At): List<String> {
        val env = Forms.env("qwerty", Forms.all.getValue("block"))
        val keys = listOf("full/g", "full/h", "full/l", "full/z")
        val marks = MarkSettings(
            upAt = at, downAt = KeyMarks.At.TopLeft.takeIf { it != at } ?: KeyMarks.At.TopRight,
            shortcuts = Fixtures.presetMarks + keys.associate { "$it:up" to MarkContent.Symbol(long) },
            user = keys.map { "$it:up" }.toSet(),
        )
        val s = Forms.scene("qwerty", env.copy(marks = marks))
        return s.keys.filter { it.shortcut in keys }.flatMap { k ->
            k.ops(false).filterIsInstance<Op.Text>().filter { it.part == "mark" }.mapNotNull { t ->
                val m = FakeMeasurer.measure(t.text, t.font, t.weight, t.size, t.letterSpacing).ink
                val left = t.x + m.left * t.scaleX
                val right = t.x + m.right * t.scaleX
                "${k.shortcut} $at ${t.text}: $left..$right of ${k.bounds.left}..${k.bounds.right}"
                    .takeIf { left < k.bounds.left || right > k.bounds.right }
            }
        }
    }

    @Test
    fun aLongFirstStaysOnItsKey() {
        assertEquals(emptyList(), (KeyMarks.At.entries - KeyMarks.At.None).flatMap(::outside))
    }

    @Test
    fun aFirstWiderThanItsKeyIsLeftOutAndSaidSoAShortOneIsDrawn() {
        // a mark with no room is left out (specs/app.md): the settings page tells it (MarkFate.Dropped)
        val env = Forms.env("qwerty", Forms.all.getValue("block"))
        val marks = MarkSettings(
            upAt = KeyMarks.At.BottomRight, downAt = KeyMarks.At.TopLeft,
            shortcuts = Fixtures.presetMarks + ("full/l:up" to MarkContent.Symbol(long)) + ("full/c:up" to MarkContent.Symbol("清账")),
            user = setOf("full/l:up", "full/c:up"),
        )
        val s = Forms.scene("qwerty", env.copy(marks = marks))
        fun key(n: String) = s.keys.first { it.shortcut == n }
        assertEquals(mapOf(true to MarkFate.Dropped), key("full/l").fates)
        assertEquals(mapOf(true to MarkFate.AsSet), key("full/c").fates)
        assertEquals(listOf("清账"), key("full/c").ops(false).filterIsInstance<Op.Text>().filter { it.part == "mark" }.map { it.text })
    }
}
