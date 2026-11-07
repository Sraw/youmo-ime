/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.input.scene.SceneBehavior.Bubble
import org.fcitx.fcitx5.android.input.scene.SceneBehavior.LongPopup
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 060-render-p2 U4 B: what a scene key does, all of it its layout's; 060-render-v2 §15: a group's letters
class SceneBehaviorTest {

    private val theme = SceneData.theme("material-light.xml")

    private fun layout(name: String): Layout = (LayoutReader.layout(SceneData.read("$name.xml")) as Parsed.Ok).value

    private val bottom: Fragment = (LayoutReader.fragment(SceneData.read("grouped-bottom.xml")) as Parsed.Ok).value

    private fun keys(name: String, input: Input = SceneData.portrait.input): Map<String, KeyNode> {
        val env = SceneData.portrait.copy(input = input)
        val built = SceneBuilder(theme, env, SceneData.measurer).build(layout(name), mapOf("grouped-bottom" to bottom))
        return (built as Parsed.Ok).value.keys.associateBy { it.id }
    }

    @Test
    fun aLayoutsWordsReadAsWhatTheyDo() {
        val words = mapOf(
            "," to SceneAction.Key(","),
            "😀" to SceneAction.Key("😀"),
            "sym:BackSpace" to SceneAction.Sym("BackSpace"),
            "layout:Symbol" to SceneAction.Layout("Symbol"),
            "picker:" to SceneAction.Picker(null),
            "picker:Emoji" to SceneAction.Picker("Emoji"),
            "commit:.com" to SceneAction.Commit(".com"),
            "code:U+FF0C" to SceneAction.Code(0xFF0C),
            "keypad:7" to SceneAction.Keypad(7),
            "caps" to SceneAction.Caps(false),
            "caps:lock" to SceneAction.Caps(true),
            "shortcut:copy" to SceneAction.Shortcut("copy"),
            "reading" to SceneAction.Reading(null),
            "reading:pinyin" to SceneAction.Reading("pinyin"),
            "emoji-search:on" to SceneAction.EmojiSearch("on"),
            "lang" to SceneAction.Command("lang"),
            "action:zhuyin" to SceneAction.Placeholder("zhuyin"),
        )
        words.forEach { (raw, action) -> assertEquals(raw, action, SceneAction.of(raw)) }
        for (none in listOf("code:FF0C", "code:U+XYZ", "keypad:12", "keypad:x", "caps:once", "launch:rockets")) {
            assertNull(none, SceneAction.of(none))
        }
    }

    @Test
    fun aGroupKeyTypesItsLettersAndOffersThemOnALongPress() {
        val qw = SceneBehavior.of(keys("k14").getValue("r1c1"))
        // no send= written: its letters= are its tap (060-render-v2 §15)
        assertEquals(SceneAction.Group("qw"), qw.press)
        assertEquals(SceneAction.Keypad(1), qw.swipe)
        assertEquals(Bubble("QW", "1"), qw.bubble)
        assertEquals(LongPopup.Letters("qw"), qw.popup)
        assertEquals("g14/qw", qw.shortcut)
        // the 18 keys' L is L; whatever its face: the group the engine reads
        val l = SceneBehavior.of(keys("k18").getValue("r2c6"))
        assertEquals(SceneAction.Group("l;"), l.press)
        assertEquals(Bubble("L", "-"), l.bubble)
        // a layout's own popup over the letters' (from="group" is theirs too)
        val listed = node("<key id=\"g\" label=\"AB\" letters=\"ab\"><popup from=\"list\"><item text=\"x\"/></popup></key>")
        assertEquals(LongPopup.Listed(listOf("x"), false), SceneBehavior.of(listed).popup)
        val group = node("<key id=\"g\" label=\"AB\" letters=\"ab\" send=\"x\"><popup from=\"group\"/></key>")
        assertEquals(LongPopup.Letters("ab"), SceneBehavior.of(group).popup)
        // a send= written is the tap, letters or not
        assertEquals(SceneAction.Key("x"), SceneBehavior.of(group).press)
    }

    private fun node(key: String): KeyNode {
        val text = "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"grouped\" columns=\"10\"><defaults kind=\"letter\"/><row>$key</row></keyboard>"
        val l = (LayoutReader.layout(text) as Parsed.Ok).value
        return (SceneBuilder(theme, SceneData.portrait, SceneData.measurer).build(l, emptyMap()) as Parsed.Ok).value.keys.single()
    }

    @Test
    fun theSharedBottomRowDoesAsTheOldKeysDid() {
        val k = keys("k14").mapValues { SceneBehavior.of(it.value) }
        val separator = k.getValue("apostrophe")
        assertEquals(SceneAction.Key("'"), separator.press)
        assertNull("a function key shows no bubble", separator.bubble)
        assertNull(separator.popup)
        val comma = k.getValue("comma")
        assertEquals(Bubble(",", null), comma.bubble)
        assertEquals(LongPopup.Preset(",", true), comma.popup)
        val space = k.getValue("space")
        assertTrue(space.bothWays)
        assertEquals("space", space.sound)
        assertEquals(SceneAction.Command("space-long-press"), space.longPress)
        val backspace = k.getValue("backspace")
        assertEquals(SceneAction.Sym("BackSpace"), backspace.press)
        assertTrue(backspace.repeat && backspace.bothWays)
        val enter = k.getValue("enter")
        assertEquals(listOf(SceneBehavior.MenuChoice("Emoji", "emoji", SceneAction.Picker(null))), enter.menu)
        // an address field's keys: the domain's list committed as it is
        val url = keys("k14", SceneData.portrait.input.copy(field = "url")).mapValues { SceneBehavior.of(it.value) }
        assertEquals(SceneAction.Commit(".com"), url.getValue("domain").press)
        assertEquals(LongPopup.Listed(listOf(".com", ".cn", ".net", ".org"), true), url.getValue("domain").popup)
        assertEquals(Bubble(".com", null), url.getValue("domain").bubble)
    }

    // the same node, the same behaviour: a scene made anew keeps the keys made for the last
    @Test
    fun aKeyThatDoesTheSameIsEqual() {
        val a = SceneBehavior.of(keys("k14").getValue("r2c1"))
        val b = SceneBehavior.of(keys("k14", SceneData.portrait.input.copy(langKey = false)).getValue("r2c1"))
        assertEquals(a, b)
    }

    // 060-render-v2 §15: the built-in 14 and 18 keys' letters= are exactly G14's and G18's groups, in order,
    // so their codes, wiring and learning record are as they were
    @Test
    fun theBuiltInLayoutsGroupsAreTheEnginesG14AndG18() {
        val k14 = SceneGroups.of(layout("k14"), mapOf("grouped-bottom" to bottom))
        assertEquals(NineKeyLetters.G14.tag(), k14.joinToString(","))
        assertEquals(NineKeyLetters.G14, SceneGroups.letters(k14))
        assertNull(SceneGroups.mismatch(k14, NineKeyLetters.G14))
        val k18 = SceneGroups.of(layout("k18"), mapOf("grouped-bottom" to bottom))
        assertEquals(NineKeyLetters.G18, SceneGroups.letters(k18))
        assertNull(SceneGroups.mismatch(k18, NineKeyLetters.G18))
        // the 14 keys' groups on the 18 keys' keyboard, or groups the engine has none of (a 17-key layout's: P3)
        assertTrue(SceneGroups.mismatch(k14, NineKeyLetters.G18)!!.contains(NineKeyLetters.G14.tag()))
        val own = listOf("pqrs", "abc")
        assertNull(SceneGroups.letters(own))
        assertTrue(SceneGroups.mismatch(own, NineKeyLetters.G14)!!.contains("pqrs,abc"))
        assertNull(SceneGroups.letters(emptyList()))
        // none on the full keyboard
        assertEquals(emptyList<String>(), SceneGroups.of(SceneData.qwerty))
    }

    @Test
    fun aGroupInAnIncludedFragmentIsWhereItIsIncluded() {
        val ns = LayoutReader.NAMESPACE
        val top = (LayoutReader.layout("<keyboard xmlns=\"$ns\" id=\"t\" name=\"t\" family=\"grouped\" columns=\"10\"><row><key id=\"a\" label=\"A\" letters=\"a\"/></row><include src=\"f\"/><row><key id=\"c\" label=\"C\" letters=\"c\"/></row></keyboard>") as Parsed.Ok).value
        val f = (LayoutReader.fragment("<fragment xmlns=\"$ns\" id=\"f\"><row><key id=\"b\" label=\"B\" letters=\"b\" shortcut=\"x/b\"/></row></fragment>") as Parsed.Ok).value
        assertEquals(listOf("a", "b", "c"), SceneGroups.of(top, mapOf("f" to f)))
        // the fragment's shortcut names too (SceneMarks)
        assertTrue("x/b" in SceneMarks.names(top, listOf(f)))
        assertTrue("x/b" !in SceneMarks.names(top))
    }
}
