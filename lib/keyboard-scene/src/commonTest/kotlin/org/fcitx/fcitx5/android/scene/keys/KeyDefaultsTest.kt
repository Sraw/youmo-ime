/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.keys

import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant.Form
import org.fcitx.fcitx5.android.scene.layout.Popup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// the expected lists are the app's before 060-keys-unify (dev/design/keys-before.tsv), not read from the generators
class KeyDefaultsTest {
    private val nine = KeyboardVariant(Form.Nine)

    private fun texts(l: DefaultList?) = l?.items?.map { it.text }

    @Test
    fun aNineDigitsRowIsCapitalsDigitLetters() {
        val two = KeyDefaults.of(KeyId("t9/2"), nine, NodeFacts(popup = Popup("nine", null, emptyList(), emptyMap())))!!
        assertEquals(listOf("A", "B", "C", "2", "a", "b", "c", "@", "!", "\""), texts(two))
        assertEquals(Item("2", Commit.Keypad('2')), two.first)
        assertEquals(3, two.focus)
        assertEquals(listOf("@", "!", "\""), two.symbols.map { it.text })
    }

    @Test
    fun nineOnesMarksGoThroughThePunctuation() {
        val one = KeyDefaults.nine('1', nine)
        assertEquals(Item("1", Commit.Keypad('1')), one.first)
        // as the punctuation maps them from (… 、 “ as Chinese punctuation has them), typed as keys, as ，'s are
        assertEquals(listOf("^", ":", ";", "~", ".", "-", "@", "\\", "\""), one.symbols.map { it.text })
        assertEquals(setOf<Commit>(Commit.AsKey), one.symbols.map { it.commit }.toSet())
    }

    @Test
    fun shuangpinsNineLettersComeFromTheVariant() {
        val sp = KeyboardVariant(Form.Nine, KeyboardVariant.STANDARD_NINE + ('7' to "pqrs;"))
        assertEquals(listOf("P", "Q", "R", "S", ";", "7", "p", "q", "r", "s", ";"), KeyDefaults.nine('7', sp).row.map { it.text })
    }

    @Test
    fun aGroupsCornerIsFirstAndItsSymbolsTheLettersOwn() {
        val qw = KeyDefaults.of(KeyId("g14/qw"), KeyboardVariant(Form.Grouped14), NodeFacts(letters = "qw", themeFirst = "keypad:1", place = 0))!!
        assertEquals(listOf("Q", "W", "1", "q", "w"), texts(qw))
        assertEquals(Item("1", Commit.Keypad('1')), qw.first)
        assertEquals(2, qw.focus)
        // no theme corner: the built-in one of its place
        val asGroup = KeyDefaults.of(KeyId("g14/as"), KeyboardVariant(Form.Grouped14), NodeFacts(letters = "as", place = 5))!!
        assertEquals(listOf("A", "S", "6", "a", "s", "@", "*"), texts(asGroup))
        val zx = KeyDefaults.grouped("zx", "~")
        assertEquals(Item("~"), zx.first)
        assertEquals(listOf("Z", "X", "~", "z", "x"), zx.row.map { it.text })
    }

    @Test
    fun aSemicolonsCapitalIsAColon() {
        assertEquals(listOf("L", ":", "l", ";"), KeyDefaults.grouped("l;", null).row.map { it.text })
    }

    @Test
    fun theFullKeyboardsLettersArePresets() {
        val full = KeyboardVariant(Form.Full)
        val q = KeyDefaults.of(KeyId("full/q"), full, NodeFacts(text = "q", typing = true, themeFirst = "1"))!!
        assertEquals(listOf("1", "Q"), texts(q))
        assertEquals(Item("1"), q.first)
        assertEquals(listOf("1", "q"), texts(KeyDefaults.of(KeyId("full/q"), full, NodeFacts(text = "q", typing = true), caps = true)))
    }

    @Test
    fun commaAndPeriodAreTheSameOnEveryKeyboard() {
        val comma = KeyDefaults.of(KeyId("fn/comma"), nine, NodeFacts())!!
        assertEquals(listOf(".", "?", "!", ":", ";", "\"", "'", "(", ")", "~"), texts(comma))
        assertEquals(Item("."), comma.first)
        assertEquals(",", KeyDefaults.function("period")?.first?.text)
        assertNull(KeyDefaults.function("space"))
    }

    @Test
    fun aListsItemsKeepTheirActions() {
        val p = Popup("list", null, listOf("˙" to "action:tone-5"), emptyMap())
        val tone = KeyDefaults.of(null, KeyboardVariant(Form.ZhuyinNine), NodeFacts(popup = p, themeFirst = "action:tone-5"))!!
        assertEquals(Item("˙", Commit.Action("action:tone-5")), tone.first)
        val asIs = Popup("list", null, listOf("。" to null, "？" to null), mapOf("as-is" to "true"))
        assertEquals(Item("。", Commit.AsIs), KeyDefaults.listed(asIs)?.first)
    }

    @Test
    fun aZhuyinGroupHasItsSymbolsAndNoFirst() {
        val g = KeyDefaults.of(KeyId("zy9/1"), KeyboardVariant(Form.ZhuyinNine), NodeFacts(text = "ㄅㄉㄚ", popup = Popup("group", null, emptyList(), emptyMap())))!!
        assertEquals(listOf("ㄅ", "ㄉ", "ㄚ"), texts(g))
        assertNull(g.first)
    }

    // 注音九键's ? and ! (function keys, no `<popup>`): their character's list, ¿ ‽ and ¡, but no first drawn or swiped to
    @Test
    fun aFunctionKeysCharacterListHasNoFirst() {
        val q = KeyDefaults.of(KeyId("zy9/question"), KeyboardVariant(Form.ZhuyinNine), NodeFacts(text = "?", typing = true, function = true))!!
        assertEquals(listOf("¿", "‽"), texts(q))
        assertNull(q.first)
        assertEquals("¿", KeyDefaults.of(KeyId("zy9/question"), KeyboardVariant(Form.ZhuyinNine), NodeFacts(text = "?", typing = true))!!.first?.text)
    }

    @Test
    fun noneAndKeysThatDoNotTypeHaveNone() {
        assertNull(KeyDefaults.of(KeyId("zyfull/1"), KeyboardVariant(Form.ZhuyinFull), NodeFacts(text = "ㄅ", typing = true, popup = Popup("none", null, emptyList(), emptyMap()))))
        assertNull(KeyDefaults.of(null, KeyboardVariant(Form.Full), NodeFacts(text = "q")))
    }

    @Test
    fun aThemesFirstNotInTheListGoesFirstAmongTheSymbols() {
        val l = KeyDefaults.of(KeyId("full/q"), KeyboardVariant(Form.Full), NodeFacts(text = "q", typing = true, themeFirst = "!"))!!
        assertEquals(listOf("!", "1", "Q"), texts(l))
        assertEquals(0, l.focus)
    }

    @Test
    fun thePresetTableReadsMarksAsWritten() {
        assertEquals(listOf("¢", "€", "£", "¥", "₹", "₽", "₺", "₩", "₱", "₿"), PresetLists.of("$"))
        assertEquals(listOf("1", "Q"), PresetLists.of("q"))
        assertNull(PresetLists.of("ㄅ"))
    }

    @Test
    fun typingFollowsTheNode() {
        assertEquals(true, KeyDefaults.typing("comma", null, null, null))
        assertEquals(true, KeyDefaults.typing(null, "letter", "q", null))
        assertEquals(false, KeyDefaults.typing(null, "letter", "q", "off"))
        assertEquals(false, KeyDefaults.typing(null, "function", "⌫", null))
    }
}
