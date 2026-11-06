/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Expand
import org.fcitx.fcitx5.android.scene.engine.ExpandedKey
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.Shift
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.layout.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 060-render-v2 §6 step 2: includes, `when=`, bindings, defaults
class ExpandTest {
    private val env = Fixtures.portrait

    private fun ids(e: Env, row: Int) =
        Expand.rows(Fixtures.qwerty, emptyMap(), e).ok()[row].items.map { (it as ExpandedKey).key.id }

    @Test
    fun theKeysShownAreAsTheInputIs() {
        assertEquals(listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"), ids(env, 1))
        val shuangpin = env.copy(input = Input(ime = "shuangpin", schemeTypes = setOf("semicolon")))
        assertEquals("semicolon", ids(shuangpin, 1).last())
        val table = env.copy(input = Input(ime = "table", table = "wubi", tableExtra = "star"))
        assertEquals("star", ids(table, 1).last())
        assertEquals("lookup", ids(table, 2).first(), "TextKeyboard.kt:300-306: 拼 instead of ⇧")
        assertEquals("lookup-on", ids(table.copy(input = table.input.copy(lookingUp = true)), 2).first())
        val url = env.copy(input = Input(field = "url"))
        assertEquals(listOf("symbols", "numbers", "slash", "space", "dot", "domain", "lang", "enter"), ids(url, 3))
        val composingTable = table.copy(input = table.input.copy(composing = true))
        assertEquals("confirm", ids(composingTable, 3).last())
        assertEquals(listOf("symbols", "numbers", "comma", "space", "period", "enter"), ids(env.copy(input = Input(langKey = false)), 3))
    }

    @Test
    fun bindings() {
        val texts = Texts(imeName = "拼音", comma = "，", period = "。", returnLabel = "发送", keepUppercase = false,
            alts = mapOf("q" to "!"), punctuation = mapOf("?" to "？"), other = mapOf("nine-letters:2" to "abc", "zhuyin-group:1" to "ㄅ"))
        val e = env.copy(texts = texts)
        assertEquals("q", Expand.text(Text.Binding("case", "q"), e))
        assertEquals("Q", Expand.text(Text.Binding("case", "q"), e.copy(shift = Shift.Once)))
        assertEquals("Q", Expand.text(Text.Binding("case", "q"), e.copy(texts = texts.copy(keepUppercase = true))))
        assertEquals("!", Expand.text(Text.Binding("alt", "q"), e))
        assertEquals("", Expand.text(Text.Binding("alt", "w"), e))
        assertEquals("？", Expand.text(Text.Binding("punct", "?"), e))
        assertEquals("!", Expand.text(Text.Binding("punct", "!"), e))
        assertEquals("，", Expand.text(Text.Binding("comma", null), e))
        assertEquals("。", Expand.text(Text.Binding("period", null), e))
        assertEquals("拼音", Expand.text(Text.Binding("ime-name", null), e))
        assertEquals("发送", Expand.text(Text.Binding("return", null), e))
        assertEquals("abc", Expand.text(Text.Binding("nine-letters", "2"), e))
        assertEquals("ㄅ", Expand.text(Text.Binding("zhuyin-group", "1"), e))
        assertEquals("", Expand.text(Text.Binding("nine-letters", "9"), e))
    }

    @Test
    fun iconBindings() {
        assertEquals("shift", Expand.icon(Text.Binding("shift-icon", null), env))
        assertEquals("shift-once", Expand.icon(Text.Binding("shift-icon", null), env.copy(shift = Shift.Once)))
        assertEquals("shift-locked", Expand.icon(Text.Binding("shift-icon", null), env.copy(shift = Shift.Lock)))
        assertEquals("enter", Expand.icon(Text.Binding("return-icon", null), env))
        assertNull(Expand.icon(Text.Binding("hold-badge", null), env))
        assertEquals("voice", Expand.icon(Text.Binding("hold-badge", null), env.copy(input = Input(holdVoice = true))))
        assertNull(Expand.icon(Text.Binding("other", null), env))
    }

    @Test
    fun aKeysAttributesOverItsRowsOverItsFiles() {
        val l = LayoutReader.layout(
            """
            <keyboard xmlns="${LayoutReader.NAMESPACE}" id="t" name="t" family="symbols" columns="10">
              <defaults kind="letter" text-size="20"/>
              <row><defaults text-size="16" kind="function"/><key id="a" label="a" kind="space"/><key id="b" label="b"/></row>
              <row><key id="c" label="c"/></row>
            </keyboard>
            """.trimIndent(),
        ).ok()
        val rows = Expand.rows(l, emptyMap(), env).ok()
        val (a, b) = rows[0].items.map { it as ExpandedKey }
        val c = rows[1].items.single() as ExpandedKey
        assertEquals("space" to 16.0, a.kind to a.attrs.textSize)
        assertEquals("function" to 16.0, b.kind to b.attrs.textSize)
        assertEquals("letter" to 20.0, c.kind to c.attrs.textSize)
        assertEquals("a", a.a11y, "a label is what a screen reader says where no a11y=")
    }

    @Test
    fun includesPutAFragmentsRowsInWithItsDefaults() {
        val l = LayoutReader.layout(
            """
            <keyboard xmlns="${LayoutReader.NAMESPACE}" id="t" name="t" family="symbols" columns="10">
              <row><key id="a" label="a"/></row>
              <include src="bottom"/>
              <include src="never" when="split"/>
            </keyboard>
            """.trimIndent(),
        ).ok()
        val bottom = LayoutReader.fragment(
            "<fragment xmlns=\"${LayoutReader.NAMESPACE}\" id=\"bottom\"><defaults kind=\"function\"/>" +
                "<row><key id=\"x\" label=\"x\"/></row><row when=\"split\"><key id=\"y\" label=\"y\"/></row></fragment>",
        ).ok()
        val rows = Expand.rows(l, mapOf("bottom" to bottom), env).ok()
        assertEquals(2, rows.size)
        assertEquals("function", (rows[1].items.single() as ExpandedKey).kind)
        val missing = Expand.rows(l, emptyMap(), env).problems()
        assertTrue(missing.single().message.contains("no such fragment"), "$missing")
    }

    @Test
    fun aRowWithNothingShownIsAProblem() {
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"10\">" +
                "<row><key id=\"a\" label=\"a\" when=\"split\"/></row></keyboard>",
        ).ok()
        assertTrue(Expand.rows(l, emptyMap(), env).problems().single().message.contains("nothing shown"))
    }
}
