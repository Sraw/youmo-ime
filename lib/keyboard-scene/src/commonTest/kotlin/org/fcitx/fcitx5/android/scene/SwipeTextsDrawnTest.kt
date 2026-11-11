/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.engine.MarkFate
import org.fcitx.fcitx5.android.scene.engine.MarkSettings
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A text the user set on a key's swipe is drawn on every key of every board, at every place, in every form
 * (0.6.0-beta2: 全键盘 j's 下滑 text left out, k's drawn): as the app gives it (two characters and 「…」), else a
 * step down where it has no room (its first character and 「…」, then the short-text icon: SceneBuilder, MarkContent.Text.ladder),
 * at the shortcuts' size; a main character never moves for it. One the user set beats a built-in mark it meets
 * (KeyMarkYield.Mark.user).
 */
class SwipeTextsDrawnTest {

    // the forms, and the upright ones on a 360 dp phone too (a 1080 px screen at 3x): narrower keys, where j's
    // descender met a mark at 底部, 左下 or 右下
    private val forms: Map<String, Env> = Forms.all + Forms.all.filterValues { !it.landscape }.map { (n, e) -> "$n@360dp" to e.copy(density = NARROW) }

    private val boards = listOf("qwerty", "nine", "k14", "k18", "zhuyin", "zhuyin-nine", "number", "symbols", "handwriting")

    // the character keys (`full/j`); a function key's marks have their own tests (BackspaceMarksTest)
    private fun names(s: Scene) = s.keys.mapNotNull { k -> k.shortcut?.takeIf { '/' in it }?.let { k to it } }

    @Test
    fun aSetTextIsDrawnOnEveryKey() {
        // the other way off: nothing but the key's own glyph in its way
        assertEquals(NO_ROOM, missing(otherOn = false))
    }

    @Test
    fun aSetTextBeatsTheBuiltInMarkItMeets() {
        // the other way on and built in (the corner symbol, a preset icon): it is the one left out
        assertEquals(NO_ROOM, missing(otherOn = true))
    }

    // j and k's down 「现在怎么样了」 (as the app gives it, 「现在…」, its shorter 「现…」) on a 360 dp phone's qwerty
    private fun owners(at: KeyMarks.At, keys: List<String> = listOf("full/j", "full/k")): Scene {
        val env = Forms.env("qwerty", Forms.all.getValue("block").copy(density = NARROW))
        val marks = MarkSettings(
            upAt = at.yielded, downAt = at,
            shortcuts = MarkSettings.PRESET + keys.associate { "$it:down" to MarkContent.Text("现在…", "现…") },
            user = keys.map { "$it:down" }.toSet(),
        )
        return Forms.scene("qwerty", env.copy(marks = marks))
    }

    // a key's texts and icons' names as drawn
    private fun texts(s: Scene, name: String) = s.keys.first { it.shortcut == name }.ops(false).mapNotNull { (it as? Op.Text)?.text ?: (it as? Op.Icon)?.name }

    // j's or k's down text as drawn: [text] (as the app gives it) and its [shorter], with 下滑 at [at]
    private fun rung(key: String, at: KeyMarks.At, text: String, shorter: String?, form: String = "block"): List<String> {
        val env = Forms.env("qwerty", Forms.all.getValue(form).copy(density = NARROW))
        val marks = MarkSettings(upAt = at.yielded, downAt = at, shortcuts = MarkSettings.PRESET + ("$key:down" to MarkContent.Text(text, shorter)), user = setOf("$key:down"))
        val alts = Forms.scene("qwerty", env.copy(marks = marks)).keys.first { it.shortcut == key }.let { k -> k.alt }
        return texts(Forms.scene("qwerty", env.copy(marks = marks)), key).filter { it != alts }
    }

    @Test
    fun eachRungAsItsRoomHasIt() {
        // the synthetic measurer's (360 dp): room as it is floating; split, j's descender at 底部 leaves room for one
        // character; compact, for the sign alone
        assertEquals(listOf("j", "现在…"), rung("full/j", KeyMarks.At.Bottom, "现在…", "现…", "floating"))
        assertEquals(listOf("j", "现…"), rung("full/j", KeyMarks.At.Bottom, "现在…", "现…", "split"))
        assertEquals(listOf("k", "现在…"), rung("full/k", KeyMarks.At.Bottom, "现在…", "现…", "split"))
        assertEquals(listOf("j", SIGN), rung("full/j", KeyMarks.At.Bottom, "现在…", "现…", "compact"))
        // a step no narrower is skipped: the sign next
        assertEquals(listOf("j", SIGN), rung("full/j", KeyMarks.At.Bottom, "现在…", "现在怎么…", "split"))
    }

    @Test
    fun eachKeySaysHowItsSetWayIsDrawn() {
        // KeyNode.fates, what the settings page tells: each rung of eachRungAsItsRoomHasIt, and a way left out
        fun fates(board: String, key: String, form: String, density: Double? = NARROW): Map<Boolean, MarkFate> {
            val f = Forms.all.getValue(form)
            val env = Forms.env(board, if (density == null) f else f.copy(density = density))
            val marks = MarkSettings(
                upAt = KeyMarks.At.Bottom.yielded, downAt = KeyMarks.At.Bottom,
                shortcuts = MarkSettings.PRESET + ("$key:down" to MarkContent.Text("现在…", "现…")), user = setOf("$key:down"),
            )
            return Forms.scene(board, env.copy(marks = marks)).keys.first { it.shortcut == key }.fates
        }
        assertEquals(mapOf(false to MarkFate.AsSet), fates("qwerty", "full/j", "floating"))
        assertEquals(mapOf(false to MarkFate.Shortened("现…")), fates("qwerty", "full/j", "split"))
        assertEquals(mapOf(false to MarkFate.Sign), fates("qwerty", "full/j", "compact"))
        // a way the user did not set says nothing
        assertEquals(emptyMap(), Forms.scene("qwerty", Forms.env("qwerty", Forms.all.getValue("block"))).keys.first { it.shortcut == "full/a" }.fates)
        // 大千 compact on a 360 dp phone: no room even for the sign on some key (NO_ROOM)
        val env = Forms.env("zhuyin", forms.getValue("compact@360dp"))
        val dropped = Forms.scene("zhuyin", env).keys.mapNotNull { it.shortcut }.filter { '/' in it }.any { k ->
            val marks = MarkSettings(upAt = KeyMarks.At.Bottom.yielded, downAt = KeyMarks.At.Bottom, shortcuts = MarkSettings.PRESET + ("$k:down" to MarkContent.Text("现在…", "现…")), user = setOf("$k:down"))
            Forms.scene("zhuyin", env.copy(marks = marks)).keys.first { it.shortcut == k }.fates[false] == MarkFate.Dropped
        }
        assertTrue(dropped, "some 大千 key leaves its set text out")
    }

    @Test
    fun theSignIsDrawnAsTheShortcutIconsAre() {
        // compact, 底部: j's sign at c's copy icon's size and colour, as every shortcut icon is
        val env = Forms.env("qwerty", Forms.all.getValue("compact").copy(density = NARROW))
        val marks = MarkSettings(upAt = KeyMarks.At.Bottom.yielded, downAt = KeyMarks.At.Bottom, shortcuts = MarkSettings.PRESET + ("full/j:down" to MarkContent.Text("现在…", "现…")), user = setOf("full/j:down"))
        val s = Forms.scene("qwerty", env.copy(marks = marks))
        fun icon(key: String, name: String) = s.keys.first { it.shortcut == key }.ops(false).filterIsInstance<Op.Icon>().first { it.name == name }
        val sign = icon("full/j", SIGN)
        val copy = icon("full/c", "content-copy")
        assertEquals(copy.box.width to copy.box.height, sign.box.width to sign.box.height)
        assertEquals(copy.tint, sign.tint)
    }

    @Test
    fun aThemesOwnSignKeepsItsWholeSquareApart() {
        // compact, 底部: the built-in sign's two lines fit under j's descender; a theme's own short-text art, its ink
        // not known, is kept apart as its whole square: clear of j's letter, or left out
        val env = Forms.env("qwerty", Forms.all.getValue("compact").copy(density = NARROW))
        val marks = MarkSettings(upAt = KeyMarks.At.Bottom.yielded, downAt = KeyMarks.At.Bottom, shortcuts = MarkSettings.PRESET + ("full/j:down" to MarkContent.Text("现在…", "现…")), user = setOf("full/j:down"))
        fun j(theme: org.fcitx.fcitx5.android.scene.theme.Theme) = Forms.scene("qwerty", env.copy(marks = marks), theme).keys.first { it.shortcut == "full/j" }
        fun ink(k: KeyNode) = k.ops(false).filterIsInstance<Op.Text>().first { it.text == "j" }.let { t ->
            val m = FakeMeasurer.measure(t.text, t.font, t.weight, t.size, t.letterSpacing).ink
            Box(t.x + m.left * t.scaleX, t.baseline + m.top, t.x + m.right * t.scaleX, t.baseline + m.bottom)
        }
        fun sign(k: KeyNode) = k.ops(false).filterIsInstance<Op.Icon>().firstOrNull { it.name == SIGN }?.box
        val builtIn = j(Fixtures.materialLight)
        val square = sign(builtIn)!!
        assertTrue(square.top < ink(builtIn).bottom, "the built-in sign's square reaches into the letter's ink: $square")
        val base = Fixtures.materialLight
        val ownIcons: Map<String, String> = base.icons + mapOf(org.fcitx.fcitx5.android.scene.Icons.SHORT_TEXT to "short.png")
        val themed = j(
            org.fcitx.fcitx5.android.scene.theme.Theme(
                base.name, base.dark, base.version, base.colors, base.styles, base.fonts, ownIcons, base.picture,
                base.variants, base.baseColors, base.darkColors, base.meta, base.systemColors, base.soundFiles,
            ),
        )
        val own = sign(themed)
        assertTrue(own == null || own.top >= ink(themed).bottom || own.left >= ink(themed).right || own.right <= ink(themed).left, "a theme's sign over j: $own")
    }

    @Test
    fun aTextWithRoomIsNotDrawnShorter() {
        // 左上, the default: k has room for 「现在…」 as the app gives it
        val s = owners(KeyMarks.At.TopLeft)
        assertTrue("现在…" in texts(s, "full/k"), "k: ${texts(s, "full/k")}")
    }

    @Test
    fun oneKeyDrawnShorterLeavesEveryOtherMarkAsItWas() {
        // 底部: whichever of j and k is drawn shorter, the others keys' marks are as with no text on j or k
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

    // every character key of [board] with a text [n] characters long on its way [w], set by the user: whether each
    // draws it, as the app shows it (two characters, then 「…」) or a step down (one and 「…」, the sign)
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
            shortcuts = MarkSettings.PRESET + keys.associate { "$it:$way" to MarkContent.Text(shown, shorter) },
            user = keys.map { "$it:$way" }.toSet(),
        )
        return names(Forms.scene(board, env.copy(marks = marks))).all { (k, _) ->
            k.ops(false).any { it is Op.Text && (it.text == shown || it.text == shorter) || it is Op.Icon && it.name == SIGN }
        }
    }

    private companion object {
        const val NARROW = 3.0
        val SIGN = MarkContent.Text.SIGN.name

        // the owner's text: 1 to 3 characters (more than two drawn as two and 「…」, the app's KeyMarkText, so
        // any longer is drawn as three is)
        const val TEXT = "现在怎么样了"
        const val LONGEST = 3

        // the 360 dp results are the synthetic measurer's (Forms), not a phone's fonts: on the emulator (360 dp, 底部) j
        // shows the sign, k 「现在…」; 紧凑 + 单手 both the sign.
        // where not even the sign (an icon, its ink a band as wide as two characters) has room on a key at some
        // place, so KeyMarkYield leaves it out: 大千's 11 keys across, compact on a 360 dp phone (its letters never
        // move for a mark)
        val NO_ROOM = listOf("compact@360dp").flatMap { f -> listOf("1", "2", "long").map { "zhuyin $f $it" } }
    }
}
