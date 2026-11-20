/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.engine.MarkSettings
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import org.fcitx.fcitx5.android.scene.skin.assertRows
import org.fcitx.fcitx5.android.scene.theme.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A text the user set on a key's swipe is drawn on every key of every board, or a step down where it has no room; a main character never moves for it. */
class SwipeTextsDrawnTest {

    // a 360 dp phone too: narrower keys, where j's descender met a mark at 底部, 左下 or 右下
    private val forms: Map<String, Env> = Forms.all + Forms.all.filterValues { !it.landscape }.map { (n, e) -> "$n@360dp" to e.copy(density = NARROW) }

    private val boards = listOf("qwerty", "nine", "k14", "k18", "zhuyin", "zhuyin-nine", "number", "symbols")

    private fun names(s: Scene) = s.keys.mapNotNull { k -> k.shortcut?.takeIf { !KeyId(it).isFunction }?.let { k to it } }

    @Test
    fun aSetTextIsDrawnOnEveryKey() = assertRows(
        listOf(
            "aSetTextIsDrawnOnEveryKey" to false,
            "aSetTextBeatsTheBuiltInMarkItMeets" to true,
        ),
        { it.first },
    ) { (_, otherOn) -> assertEquals(NO_ROOM, missing(otherOn)) }

    private fun owners(at: KeyMarks.At, keys: List<String> = listOf("full/j", "full/k")): Scene {
        val env = Forms.env("qwerty", Forms.all.getValue("block").copy(density = NARROW))
        val marks = MarkSettings(
            upAt = at.yielded, downAt = at,
            shortcuts = Fixtures.presetMarks + keys.associate { "$it:down" to MarkContent.Text("现在…", "现…") },
            user = keys.map { "$it:down" }.toSet(),
        )
        return Forms.scene("qwerty", env.copy(marks = marks))
    }

    private fun texts(s: Scene, name: String) = s.keys.first { it.shortcut == name }.ops(false).mapNotNull { (it as? Op.Text)?.text ?: (it as? Op.Icon)?.name }

    private fun rung(key: String, at: KeyMarks.At, text: String, shorter: String?, form: String = "block"): List<String> {
        val env = Forms.env("qwerty", Forms.all.getValue(form).copy(density = NARROW))
        val marks = MarkSettings(upAt = at.yielded, downAt = at, shortcuts = Fixtures.presetMarks + ("$key:down" to MarkContent.Text(text, shorter)), user = setOf("$key:down"))
        val s = Forms.scene("qwerty", env.copy(marks = marks))
        val alts = s.keys.first { it.shortcut == key }.alt
        return texts(s, key).filter { it != alts }
    }

    @Test
    fun eachRungAsItsRoomHasIt() {
        assertEquals(listOf("j", "现在…"), rung("full/j", KeyMarks.At.Bottom, "现在…", "现…", "floating"))
        assertEquals(listOf("j", "现…"), rung("full/j", KeyMarks.At.Bottom, "现在…", "现…", "split"))
        assertEquals(listOf("k", "现在…"), rung("full/k", KeyMarks.At.Bottom, "现在…", "现…", "split"))
        assertEquals(listOf("j", SIGN), rung("full/j", KeyMarks.At.Bottom, "现在…", "现…", "compact"))
        assertEquals(listOf("j", SIGN), rung("full/j", KeyMarks.At.Bottom, "现在…", "现在怎么…", "split"))
    }

    @Test
    fun eachKeySaysHowItsSetWayIsDrawn() {
        fun fates(board: String, key: String, form: String, density: Double? = NARROW): Map<Boolean, MarkFate> {
            val f = Forms.all.getValue(form)
            val env = Forms.env(board, if (density == null) f else f.copy(density = density))
            val marks = MarkSettings(
                upAt = KeyMarks.At.Bottom.yielded, downAt = KeyMarks.At.Bottom,
                shortcuts = Fixtures.presetMarks + ("$key:down" to MarkContent.Text("现在…", "现…")), user = setOf("$key:down"),
            )
            return Forms.scene(board, env.copy(marks = marks)).keys.first { it.shortcut == key }.fates
        }
        assertEquals(mapOf(false to MarkFate.AsSet), fates("qwerty", "full/j", "floating"))
        assertEquals(mapOf(false to MarkFate.Shortened("现…")), fates("qwerty", "full/j", "split"))
        assertEquals(mapOf(false to MarkFate.Sign), fates("qwerty", "full/j", "compact"))
        val both = Forms.env("qwerty", Forms.all.getValue("compact").copy(density = NARROW)).let { env ->
            val marks = MarkSettings(
                upAt = KeyMarks.At.Bottom, downAt = KeyMarks.At.TopLeft,
                shortcuts = Fixtures.presetMarks + ("full/j:up" to MarkContent.Text("现在…", "现…")) + ("full/j:down" to MarkContent.Icon("content-paste")),
                user = setOf("full/j:up", "full/j:down"),
            )
            Forms.scene("qwerty", env.copy(marks = marks)).keys.first { it.shortcut == "full/j" }.fates
        }
        assertEquals(mapOf(true to MarkFate.Sign, false to MarkFate.AsSet), both)
        assertEquals(emptyMap(), Forms.scene("qwerty", Forms.env("qwerty", Forms.all.getValue("block"))).keys.first { it.shortcut == "full/a" }.fates)
        val env = Forms.env("zhuyin", forms.getValue("compact@360dp"))
        val dropped = Forms.scene("zhuyin", env).keys.mapNotNull { it.shortcut }.filter { !KeyId(it).isFunction }.any { k ->
            val marks = MarkSettings(upAt = KeyMarks.At.Bottom.yielded, downAt = KeyMarks.At.Bottom, shortcuts = Fixtures.presetMarks + ("$k:down" to MarkContent.Text("现在…", "现…")), user = setOf("$k:down"))
            Forms.scene("zhuyin", env.copy(marks = marks)).keys.first { it.shortcut == k }.fates[false] == MarkFate.Dropped
        }
        assertTrue(dropped, "some 大千 key leaves its set text out")
    }

    @Test
    fun theSignIsDrawnAsTheShortcutIconsAre() {
        val env = Forms.env("qwerty", Forms.all.getValue("compact").copy(density = NARROW))
        val marks = MarkSettings(upAt = KeyMarks.At.Bottom.yielded, downAt = KeyMarks.At.Bottom, shortcuts = Fixtures.presetMarks + ("full/j:down" to MarkContent.Text("现在…", "现…")), user = setOf("full/j:down"))
        val s = Forms.scene("qwerty", env.copy(marks = marks))
        fun icon(key: String, name: String) = s.keys.first { it.shortcut == key }.ops(false).filterIsInstance<Op.Icon>().first { it.name == name }
        val sign = icon("full/j", SIGN)
        val copy = icon("full/c", "content-copy")
        assertEquals(copy.box.width to copy.box.height, sign.box.width to sign.box.height)
        assertEquals(copy.tint, sign.tint)
    }

    @Test
    fun aThemesOwnSignKeepsItsWholeSquareApart() {
        // a theme's own short-text art, its ink not known, is kept apart as its whole square
        val env = Forms.env("qwerty", Forms.all.getValue("compact").copy(density = NARROW))
        val marks = MarkSettings(upAt = KeyMarks.At.Bottom.yielded, downAt = KeyMarks.At.Bottom, shortcuts = Fixtures.presetMarks + ("full/j:down" to MarkContent.Text("现在…", "现…")), user = setOf("full/j:down"))
        fun j(theme: Theme) = Forms.scene("qwerty", env.copy(marks = marks), theme).keys.first { it.shortcut == "full/j" }
        fun ink(k: KeyNode) = k.ops(false).filterIsInstance<Op.Text>().first { it.text == "j" }.let { t ->
            val m = FakeMeasurer.measure(t.text, t.font, t.weight, t.size, t.letterSpacing).ink
            Box(t.x + m.left * t.scaleX, t.baseline + m.top, t.x + m.right * t.scaleX, t.baseline + m.bottom)
        }
        fun sign(k: KeyNode) = k.ops(false).filterIsInstance<Op.Icon>().firstOrNull { it.name == SIGN }?.box
        val builtIn = j(Fixtures.materialLight)
        val square = sign(builtIn)!!
        assertTrue(square.top < ink(builtIn).bottom, "the built-in sign's square reaches into the letter's ink: $square")
        val base = Fixtures.materialLight
        val ownIcons: Map<String, String> = base.icons + mapOf(Icons.SHORT_TEXT to "short.png")
        // Theme is no data class: every field but the icons as the base's, by name
        val themed = j(
            Theme(
                name = base.name, dark = base.dark, version = base.version, colors = base.colors, styles = base.styles,
                fonts = base.fonts, icons = ownIcons, picture = base.picture, variants = base.variants,
                baseColors = base.baseColors, darkColors = base.darkColors, meta = base.meta,
                systemColors = base.systemColors, soundFiles = base.soundFiles,
            ),
        )
        val own = sign(themed)
        assertTrue(own == null || own.top >= ink(themed).bottom || own.left >= ink(themed).right || own.right <= ink(themed).left, "a theme's sign over j: $own")
        assertEquals(mapOf(false to if (own == null) MarkFate.Dropped else MarkFate.Sign), themed.fates)
    }

    @Test
    fun aTextWithRoomIsNotDrawnShorter() {
        val s = owners(KeyMarks.At.TopLeft)
        assertTrue("现在…" in texts(s, "full/k"), "k: ${texts(s, "full/k")}")
    }

    @Test
    fun oneKeyDrawnShorterLeavesEveryOtherMarkAsItWas() {
        val with = owners(KeyMarks.At.Bottom)
        val without = owners(KeyMarks.At.Bottom, keys = emptyList())
        val others = with.keys.filter { it.shortcut != "full/j" && it.shortcut != "full/k" }.map { it.shortcut to it.ops(false) }
        assertEquals(without.keys.filter { it.shortcut != "full/j" && it.shortcut != "full/k" }.map { it.shortcut to it.ops(false) }, others)
        assertTrue(listOf("full/j", "full/k").all { n -> texts(with, n).any { it == "现在…" || it == "现…" || it == SIGN } }, "j and k draw it")
    }

    private fun missing(otherOn: Boolean): List<String> =
        boards.flatMap { board -> forms.map { (form, env) -> Triple(board, form, Forms.env(board, env)) } }
            .flatMap { (board, form, env) ->
                val keys = names(Forms.scene(board, env)).map { it.second }.toSet()
                val ways = (KeyMarks.At.entries - KeyMarks.At.None).flatMap { at -> listOf(true, false).map { at to it } }
                (1..LONGEST).filter { n -> ways.any { (at, up) -> !allDrawn(board, env, keys, Way(at, up, otherOn), n) } }
                    .map { "$board $form ${if (it > 2) "long" else it}" }
            }.distinct().sorted()

    private class Way(val at: KeyMarks.At, val up: Boolean, val otherOn: Boolean)

    private fun allDrawn(board: String, env: Env, keys: Set<String>, w: Way, n: Int): Boolean {
        val way = if (w.up) "up" else "down"
        val text = TEXT.take(n)
        val shown = if (n > 2) text.take(2) + "…" else text
        val shorter = if (n > 1) text.take(1) + "…" else null
        val marks = MarkSettings(
            upAt = if (w.up) w.at else w.at.yielded,
            downAt = if (w.up) w.at.yielded else w.at,
            upOn = w.up || w.otherOn,
            downOn = !w.up || w.otherOn,
            shortcuts = Fixtures.presetMarks + keys.associate { "$it:$way" to MarkContent.Text(shown, shorter) },
            user = keys.map { "$it:$way" }.toSet(),
        )
        return names(Forms.scene(board, env.copy(marks = marks))).all { (k, _) ->
            k.ops(false).any { it is Op.Text && (it.text == shown || it.text == shorter) || it is Op.Icon && it.name == SIGN }
        }
    }

    private companion object {
        const val NARROW = 3.0
        val SIGN = MarkContent.Text.SIGN.name

        const val TEXT = "现在怎么样了"
        const val LONGEST = 3

        // the synthetic measurer's results (Forms), not a phone's fonts: where not even the sign has room
        val NO_ROOM = listOf("compact@360dp").flatMap { f -> listOf("1", "2", "long").map { "zhuyin $f $it" } }
    }
}
