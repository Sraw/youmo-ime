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
import org.fcitx.fcitx5.android.scene.engine.LetterCase
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

    // 060-render-p3 T5: the input method, the field and the board as the theme's scenes; the field's kinds
    // past an address's (a password, a number) leave the keys shown as they were
    @Test
    fun whatIsTypedIsTheThemesScenes() {
        val typed = env.copy(input = Input(ime = "table", table = "wubi", field = "password"), board = "nine")
        assertEquals(setOf("im=table", "im=wubi", "field=password", "keyboard=nine"), typed.scenes - env.scenes.filterNot { it.startsWith("im=") }.toSet())
        assertEquals(setOf("im=pinyin"), env.scenes.filter { '=' in it }.toSet(), "a text field and no board: the input method alone")
        assertEquals(setOf("im=shuangpin", "field=url"), env.copy(input = Input(ime = "shuangpin", field = "url")).scenes.filter { '=' in it }.toSet())
        assertEquals(emptySet(), env.copy(input = Input(ime = null, field = null)).scenes.filter { '=' in it }.toSet())
        // the input method's own word of it, where the app knows it: the board's Input left to its when=
        val told = env.copy(input = Input(ime = "pinyin", field = "url"), typed = setOf("im=english", "field=number"))
        assertEquals(setOf("im=english", "field=number"), told.scenes.filter { '=' in it }.toSet())
        for (field in listOf("password", "number")) {
            for (row in 0..3) assertEquals(ids(env, row), ids(env.copy(input = env.input.copy(field = field)), row), "$field, row $row")
        }
    }

    @Test
    fun bindings() {
        val texts = Texts(imeName = "拼音", comma = "，", period = "。", returnLabel = "发送", keepUppercase = false,
            alts = mapOf("q" to "!"), punctuation = mapOf("?" to "？"), other = mapOf("zhuyin-group:1" to "ㄅ"))
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
        // a table's 确定 as the app's language has it (key_confirm), 确定 by default
        assertEquals("确定", Expand.text(Text.Binding("confirm", null), e))
        assertEquals("OK", Expand.text(Text.Binding("confirm", null), e.copy(texts = texts.copy(confirm = "OK"))))
        // rules C §2: pinyin's in capitals, English's as Shift has them
        assertEquals("ABC", Expand.text(Text.Binding("nine-letters", "2"), e))
        assertEquals("wxyz", Expand.text(Text.Binding("nine-letters", "9"), e.copy(input = Input(ime = "english"))))
        assertEquals("WXYZ", Expand.text(Text.Binding("nine-letters", "9"), e.copy(input = Input(ime = "english"), shift = Shift.Once)))
        assertEquals("JKL;", Expand.text(Text.Binding("nine-letters", "5"), e.copy(texts = texts.copy(nineLetters = mapOf("5" to "jkl;")))))
        assertEquals("ㄅ", Expand.text(Text.Binding("zhuyin-group", "1"), e))
        assertEquals("", Expand.text(Text.Binding("nine-letters", "1"), e))
    }

    @Test
    fun theFirstVariantThatHoldsIsLaidOver() {
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"10\">" +
                "<row><key id=\"one\" label=\"，。？！\" text-size=\"16\" alt=\"1\">" +
                "<variant when=\"composing ime=english\" label=\"'\"/><variant when=\"composing\" label=\"分词\"/>" +
                "<variant when=\"ime=english\" label=\".,?!\"/></key></row></keyboard>",
        ).ok()
        fun one(e: Env) = Expand.rows(l, emptyMap(), e).ok().single().items.single() as ExpandedKey
        assertEquals("，。？！", one(env).label)
        assertEquals("分词", one(env.copy(input = Input(composing = true))).label)
        assertEquals(16.0, one(env.copy(input = Input(composing = true))).attrs.textSize, "the rest the key's")
        assertEquals("1", one(env.copy(input = Input(composing = true))).alt)
        assertEquals(".,?!", one(env.copy(input = Input(ime = "english"))).label)
        assertEquals("'", one(env.copy(input = Input(ime = "english", composing = true))).label, "the first that holds")
    }

    @Test
    fun twoKeysOfOneIdShownIsAProblem() {
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"10\">" +
                "<row><key id=\"minus\" label=\"-\" when=\"pad=signed|signed-decimal\"/>" +
                "<key id=\"minus\" label=\"-\" when=\"pad=signed|phone\"/></row></keyboard>",
        ).ok()
        assertEquals(1, Expand.rows(l, emptyMap(), env.copy(input = Input(pad = "phone"))).ok().single().items.size)
        assertTrue(Expand.rows(l, emptyMap(), env.copy(input = Input(pad = "signed"))).problems().single().message.contains("two keys"))
    }

    @Test
    fun iconBindings() {
        assertEquals("shift", Expand.icon(Text.Binding("shift-icon", null), env))
        assertEquals("shift-once", Expand.icon(Text.Binding("shift-icon", null), env.copy(shift = Shift.Once)))
        assertEquals("shift-locked", Expand.icon(Text.Binding("shift-icon", null), env.copy(shift = Shift.Lock)))
        assertEquals("enter", Expand.icon(Text.Binding("return-icon", null), env))
        assertEquals("lock-open", Expand.icon(Text.Binding("lock-icon", null), env))
        assertEquals("lock", Expand.icon(Text.Binding("lock-icon", null), env.copy(states = mapOf("lock" to setOf(KeyState.Locked)))))
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

    @Test
    fun letterCaseSettingCasesTheLetterFacesOnly() {
        val lower = env.copy(texts = Texts(letterCase = LetterCase.Lower))
        val upper = env.copy(texts = Texts(letterCase = LetterCase.Upper))
        val q = Text.Binding("case", "q")
        assertEquals("q", Expand.text(q, lower.copy(shift = Shift.Lock)))
        assertEquals("q", Expand.text(q, lower.copy(texts = lower.texts.copy(keepUppercase = true))))
        assertEquals("Q", Expand.text(q, upper))
        // 14/18 键: capitals following Shift (none there), as before; the setting over them
        assertEquals("QW", Expand.text(Text.Binding("group", "qw"), env))
        assertEquals("qw", Expand.text(Text.Binding("group", "qw"), lower))
        assertEquals("l;", Expand.text(Text.Binding("group", "l;"), lower))
        // 九键 pinyin: capitals, or the setting's
        assertEquals("ABC", Expand.text(Text.Binding("nine-letters", "2"), env))
        assertEquals("abc", Expand.text(Text.Binding("nine-letters", "2"), lower))
        // a table's letter with no root: its letter as {case:x}; one with its root as given
        assertEquals("Z", Expand.text(Text.Binding("root", "z"), upper))
        assertEquals("z", Expand.text(Text.Binding("root", "z"), upper.copy(texts = upper.texts.copy(letterCase = LetterCase.Shift))))
        assertEquals("王", Expand.text(Text.Binding("root", "g"), upper.copy(texts = upper.texts.copy(other = mapOf("root:g" to "王")))))
        // the punctuation a case binding may carry is as it was
        assertEquals(",", Expand.text(Text.Binding("case", ","), upper))
    }

    @Test
    fun aScreenReaderHearsTheLetterAsFollowingShiftShowsIt() {
        val l = LayoutReader.layout(
            """
            <keyboard xmlns="${LayoutReader.NAMESPACE}" id="t" name="t" family="symbols" columns="10">
              <row><key id="q" label="{case:q}"/><key id="g" label="{group:qw}" letters="qw" alt="1"/></row>
            </keyboard>
            """.trimIndent(),
        ).ok()
        fun keys(e: Env) = Expand.rows(l, emptyMap(), e).ok()[0].items.map { it as ExpandedKey }
        val shown = keys(env)
        listOf(LetterCase.Lower, LetterCase.Upper).forEach { c ->
            val k = keys(env.copy(texts = env.texts.copy(letterCase = c)))
            assertEquals(shown.map { it.a11y }, k.map { it.a11y }, "$c")
        }
        assertEquals("Q", keys(env.copy(texts = env.texts.copy(letterCase = LetterCase.Upper)))[0].label)
        assertEquals("q", shown[0].a11y)
        assertEquals("Q W, 1", shown[1].a11y)
    }
}
