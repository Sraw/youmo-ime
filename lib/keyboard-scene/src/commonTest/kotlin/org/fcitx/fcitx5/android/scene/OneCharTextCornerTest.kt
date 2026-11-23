/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Content
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Expand
import org.fcitx.fcitx5.android.scene.engine.ExpandedKey
import org.fcitx.fcitx5.android.scene.engine.Looks
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.rules.KeyMarkYield
import org.fcitx.fcitx5.android.scene.skin.assertRows
import org.fcitx.fcitx5.android.scene.theme.TextLook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A user's 文字 slot of one character is drawn as a 长按首选 corner is: a QQ group's 九键 had 「1」 set on 1 and 「0」
 * on the space drawn smaller and thinner than 2's and 3's corners.
 */
class OneCharTextCornerTest {

    private fun style(t: Op.Text) = listOf(t.font, t.weight, t.size, t.color)

    private fun env(set: Map<String, MarkContent>, board: String = "nine", form: Env = Forms.all.getValue("block")): Env {
        val base = Forms.env(board, form)
        return base.copy(marks = base.marks.copy(shortcuts = base.marks.shortcuts + set, user = set.keys))
    }

    private fun scene(set: Map<String, MarkContent>): Scene = Forms.scene("nine", env(set))

    private fun corner(s: Scene, name: String, text: String): Op.Text =
        s.keys.first { it.shortcut == name }.ops(false).filterIsInstance<Op.Text>().first { it.text == text }

    private class Row(val why: String, val name: String, val text: String, val check: (two: Op.Text, it: Op.Text) -> Unit)

    private val alike: (Op.Text, Op.Text) -> Unit = { two, t -> assertEquals(style(two), style(t)) }

    private val sized: (Op.Text, Op.Text) -> Unit = { two, t -> assertEquals(listOf(two.weight, two.size), listOf(t.weight, t.size), "drawn as ${style(t)}") }

    private val rows = listOf(
        Row("on 1, as 2's 「2」 beside it", "t9/1", "1", alike),
        Row("on the space", "fn/space", "0", alike),
        Row("a longer text keeps the shortcut's look", "t9/1", "12") { two, t ->
            assertTrue(t.size < two.size && t.weight < two.weight, "「12」 ${style(t)} beside 「2」 ${style(two)}")
        },
        Row("two clusters stay a text", "t9/1", "❤️❤️") { two, t -> assertTrue(t.weight < two.weight, "drawn as ${style(t)}") },
    ) + listOf(
        // ❤️ with its VS16, a flag, a ZWJ family, a keycap, e and a combining acute, 中, 𠀀 past the BMP, 👍 and its skin tone
        "❤️", "🇨🇳", "👨‍👩‍👧", "1️⃣", "é", "中", "𠀀", "👍🏽",
    ).map { Row("one cluster is one character, not one code point", "t9/1", it, sized) }

    @Test
    @Uat("T9-71")
    fun aOneCharacterTextLooksAsTheFirstsBesideIt() = assertRows(rows, { "「${it.text}」 on ${it.name}: ${it.why}" }) {
        val s = scene(mapOf("${it.name}:up" to MarkContent.Text(it.text)))
        it.check(corner(s, "t9/2", "2"), corner(s, it.name, it.text))
    }

    @Test
    @Uat("T9-71")
    fun aRealShortcutBesideItStaysTheFirstShortcut() {
        val e = env(mapOf("t9/1:up" to MarkContent.Text("1"), "t9/1:down" to MarkContent.Text("12")))
        val key = Expand.rows(Forms.layout("nine"), Forms.fragments, e).ok().flatMap { it.items }.filterIsInstance<ExpandedKey>()
            .first { it.attrs.shortcut == "t9/1" }
        val cell = Box(0.0, 0.0, 120 * e.density, 60 * e.density)
        val looks = Looks { TextLook(emptyMap(), Fixtures.materialLight) }
        val marks = Content(e, FakeMeasurer).of(key, cell, cell, looks).marks
        assertEquals(KeyMarkYield.DROP_SYMBOL, marks.single { it.way?.up == true }.drop)
        // else the down text is left out sooner than a first shortcut is
        assertEquals(KeyMarkYield.DROP_SHORTCUT, marks.single { it.way?.up == false }.drop)
    }

    @Test
    @Uat("T9-71")
    fun crowdedOnANarrowKeyItTakesTheTextsLook() {
        fun drawn(form: Env): Pair<Op.Text, MarkFate?> {
            val s = Forms.scene("zhuyin", env(mapOf("zyfull/3:up" to MarkContent.Text("中")), "zhuyin", form))
            val key = s.keys.first { it.shortcut == "zyfull/3" }
            return key.ops(false).filterIsInstance<Op.Text>().first { it.text == "中" } to key.fates[true]
        }
        val (roomy, _) = drawn(Forms.all.getValue("block"))
        // 360 dp, 大千 compact: the symbol class shrinks while no shortcut class does
        val (narrow, fate) = drawn(Forms.all.getValue("compact").copy(density = 3.0))
        assertTrue(narrow.weight < roomy.weight, "narrow ${style(narrow)}, roomy ${style(roomy)}")
        assertEquals(MarkFate.AsSet, fate)
    }
}
