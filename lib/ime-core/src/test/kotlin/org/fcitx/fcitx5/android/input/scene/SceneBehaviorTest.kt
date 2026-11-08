/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.core.CoreLog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
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
        val nones = listOf(
            "code:FF0C", "code:U+XYZ", "code:U+110000", "code:U+D800", "code:U+DFFF", "keypad:12", "keypad:x", "caps:once",
            "launch:rockets", "layout:", "emoji-search:maybe", "emoji-search:",
        )
        val logged = mutableListOf<String>()
        val sink = CoreLog.errorSink
        CoreLog.errorSink = { logged += it }
        try {
            for (none in nones) {
                assertNull(none, SceneAction.of(none))
                // logged, each
                assertTrue(none, logged.removeLast().contains("\"$none\""))
                // the reader takes none of them either: the two agree
                assertNull(none, LayoutReader.action(none))
            }
        } finally {
            CoreLog.errorSink = sink
        }
        assertEquals(SceneAction.Code(0x10FFFF), SceneAction.of("code:U+10FFFF"))
    }

    // every form the reader takes is an action the app reads (one of each of layout-1's patterns, the
    // edges of each): none it lets through reads as nothing
    @Test
    fun everyWordTheReaderTakesIsAnAction() {
        val forms = listOf(
            "q", ":", "😀", "sym:BackSpace", "sym:", "picker:", "picker:Emoji", "shortcut:copy", "shortcut:", "reading:", "reading:pinyin",
            "layout:Symbol", "layout:a", "emoji-search:on", "emoji-search:off", "action:zhuyin", "action:zhuyin-slot", "action:tone-1",
            "action:tone-5", "commit:.com", "commit::", "code:U+0000", "code:U+FF0C", "code:U+F0104", "code:U+10FFFF", "keypad:0",
            "keypad:9", "caps", "caps:lock", "lang", "ime-picker", "quickphrase", "unicode", "panel-back", "panel-lock",
            "space-long-press", "reading", "begin-selection-swipe", "delete-selection", "move-selection", "lookup",
        )
        for (form in forms) {
            assertEquals(form, form, LayoutReader.action(form))
            assertTrue(form, SceneAction.of(form) != null)
        }
    }

    // a send= written that reads as nothing is nothing (logged), not the group's code its letters= would give
    @Test
    fun aSendWrittenThatReadsAsNothingIsNoGroupsCode() {
        val n = keys("k14").getValue("r1c1")
        val bad = KeyNode(
            id = n.id, copy = n.copy, kind = n.kind, role = n.role, shape = n.shape, bounds = n.bounds, hit = n.hit, anchor = n.anchor,
            up = n.up, states = n.states, faces = n.faces, a11y = n.a11y, actions = n.actions + ("send" to "launch:rockets"),
            letters = n.letters, lean = n.lean,
        )
        assertNull(SceneBehavior.of(bad).press)
        assertEquals(SceneAction.Group("qw"), SceneBehavior.of(n).press)
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

    // 大千 (060-render-p2 S4c): a symbol's and a tone's tap the keyboard's table's, no long press, the
    // bubble its face both ways (AltPreview(c, c)): swiped, the shortcut it reaches shows in its place
    @Test
    fun dachensKeysTypeByTheTableAndTheirBubbleShowsTheShortcutSwipedTo() {
        val k = keys("zhuyin").mapValues { SceneBehavior.of(it.value) }
        val q = k.getValue("q")
        assertEquals(SceneAction.Placeholder("zhuyin"), q.press)
        assertEquals(Bubble("ㄆ", "ㄆ"), q.bubble)
        assertNull(q.popup)
        assertNull(q.swipe)
        assertEquals("zyfull/q", q.shortcut)
        assertEquals(mapOf("3" to "tone-3", "4" to "tone-4", "6" to "tone-2", "7" to "tone-5"), listOf("3", "4", "6", "7").associateWith { (k.getValue(it).press as SceneAction.Placeholder).name })
        assertEquals(Bubble("ˇ", "ˇ"), k.getValue("3").bubble)
        // a letter of no shortcut its face alone; a swipe's own corner whatever its shortcut
        assertEquals(Bubble("A", null), SceneBehavior.of(node("<key id=\"a\" label=\"A\" send=\"a\"/>")).bubble)
        assertEquals(Bubble("A", "!"), SceneBehavior.of(node("<key id=\"a\" label=\"A\" send=\"a\" swipe-up=\"!\" shortcut=\"x/a\"/>")).bubble)
        // the bottom row's comma (a function key's kind) as it was: no corner
        assertEquals(Bubble(",", null), k.getValue("comma").bubble)
    }

    // 注音九键 (060-render-p2 S4d) as its views: a group types by the table, its bubble its face both ways, its
    // long press the app's (from="group", no letters=); the tones and ? ! ~ show their bubble though function
    // keys (preview="on"), ? ! ~ offering their presets (SymbolKey's); the slot none though a letter key (preview="off")
    @Test
    fun zhuyinNinesKeysBubbleAsTheirViews() {
        val k = keys("zhuyin-nine").mapValues { SceneBehavior.of(it.value) }
        val g = k.getValue("r1c2")
        assertEquals(SceneAction.Placeholder("zhuyin"), g.press)
        assertEquals(Bubble("ㄅㄉㄚ", "ㄅㄉㄚ"), g.bubble)
        assertEquals(SceneBehavior.LongPopup.Composed("group"), g.popup)
        assertEquals("zy9/1", g.shortcut)
        val tone = k.getValue("r1c1")
        assertEquals(Bubble("ˉ", null), tone.bubble)
        assertEquals(SceneAction.Placeholder("tone-5"), tone.swipe)
        assertEquals(SceneBehavior.LongPopup.Listed(listOf("˙"), false), tone.popup)
        for ((id, mark) in listOf("r2c1" to "ˊ", "r3c1" to "ˇ", "r4c1" to "ˋ")) {
            assertEquals(Bubble(mark, null), k.getValue(id).bubble)
            assertNull(k.getValue(id).popup)
        }
        for ((id, c) in listOf("r2c5" to "?", "r3c5" to "!", "r4c5" to "~")) {
            assertEquals(Bubble(c, null), k.getValue(id).bubble)
            assertEquals(SceneBehavior.LongPopup.Preset(c, true), k.getValue(id).popup)
            assertEquals(SceneAction.Key(c), k.getValue(id).press)
        }
        val slot = k.getValue("r4c4")
        assertNull(slot.bubble)
        assertEquals(SceneAction.Placeholder("zhuyin-slot"), slot.press)
        assertEquals(SceneBehavior.LongPopup.Listed(listOf("。", "？", "！", "、", "：", "；"), true), slot.popup)
        // preview= over the kind's either way; unwritten, the kind's
        assertNull(SceneBehavior.of(node("<key id=\"a\" label=\"A\" preview=\"off\" send=\"a\"/>")).bubble)
        assertEquals(Bubble("+", null), SceneBehavior.of(node("<key id=\"a\" kind=\"function\" label=\"+\" preview=\"on\" send=\"+\"/>")).bubble)
        assertNull(SceneBehavior.of(node("<key id=\"a\" kind=\"function\" label=\"+\" send=\"+\"/>")).bubble)
    }

    // <defaults preview="off"> a key's preview="on" overrides; what preview says decides the default preset too
    // (060-render-p2 S4d M1, M2)
    @Test
    fun aDefaultsPreviewOffAKeysOnOverrides() {
        val text = "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"grouped\" columns=\"10\">" +
            "<defaults kind=\"letter\" preview=\"off\"/><row><key id=\"a\" label=\"?\" preview=\"on\" send=\"?\"/>" +
            "<key id=\"b\" label=\"!\" send=\"!\"/></row></keyboard>"
        val l = (LayoutReader.layout(text) as Parsed.Ok).value
        val k = (SceneBuilder(theme, SceneData.portrait, SceneData.measurer).build(l, emptyMap()) as Parsed.Ok).value.keys.associate { it.id to SceneBehavior.of(it) }
        assertEquals(Bubble("?", null), k.getValue("a").bubble)
        assertEquals(SceneBehavior.LongPopup.Preset("?", true), k.getValue("a").popup)
        assertNull(k.getValue("b").bubble)
        assertNull(k.getValue("b").popup)
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

    // 060-render-v2 §15 按落点加权: the 14 keys' two-letter groups lean, as they did (GroupedKeyboard.tapped);
    // the 18 keys' none (lean="off"); a group of one letter has nowhere to lean
    @Test
    fun theFourteenKeysTwoLetterGroupsLeanTheEighteenKeysNone() {
        val k14 = SceneGroups.leaning(layout("k14"), mapOf("grouped-bottom" to bottom))
        assertEquals(NineKeyLetters.G14.tag().split(',').filter { it.length >= 2 }.toSet(), k14)
        assertEquals(emptySet<String>(), SceneGroups.leaning(layout("k18"), mapOf("grouped-bottom" to bottom)))
        assertTrue(keys("k14").getValue("r1c1").lean)
        assertTrue(keys("k18").values.none { it.lean })
        // the engine has places for the 14 keys' codes alone (Keyboard.TAPS): G18's go as codes (P3)
        assertTrue(NineKeyLetters.G14.tag().split(',').all { Keyboard.placesTaps(NineKeyLetters.G14.key(it[0])) })
        assertTrue(NineKeyLetters.G18.tag().split(',').none { Keyboard.placesTaps(NineKeyLetters.G18.key(it[0])) })
    }

    // which groups lean on a keyboard: the views' as they always were (G14's two-letter groups, G18's none);
    // the scene's by its layout's lean=, its groups the keyboard's; a layout of other groups gone by, lean= and all
    @Test
    fun aKeyboardsLeaningGroupsAreItsLayoutsWhereTheSceneDrawsItAndItsGroupsAreTheKeyboards() {
        val twos = NineKeyLetters.G14.tag().split(',').filter { it.length >= 2 }.toSet()
        assertEquals(twos, SceneGroups.builtInLeaning(NineKeyLetters.G14))
        assertEquals(emptySet<String>(), SceneGroups.builtInLeaning(NineKeyLetters.G18))
        assertEquals(emptySet<String>(), SceneGroups.builtInLeaning(NineKeyLetters.NINE_SP))
        assertEquals(twos, SceneGroups.leaningOf(NineKeyLetters.G14, null))
        val frags = mapOf("grouped-bottom" to bottom)
        val offK14 = (LayoutReader.layout(SceneData.read("k14.xml").replace("letters=\"qw\"", "letters=\"qw\" lean=\"off\"")) as Parsed.Ok).value
        assertEquals(twos - "qw", SceneGroups.leaningOf(NineKeyLetters.G14, offK14, frags))
        // the 14 keys' layout on the 18 keys' keyboard: G18 kept, and its leaning
        assertEquals(emptySet<String>(), SceneGroups.leaningOf(NineKeyLetters.G18, layout("k14"), frags))
        // other groups (a 17-key layout's): the 14 keys' leaning as built in, not the layout's lean="off"
        val other = (LayoutReader.layout(SceneData.read("k14.xml").replace("letters=\"bn\"", "letters=\"bn\" lean=\"off\"").replace("letters=\"m\"", "letters=\"mz\"")) as Parsed.Ok).value
        assertTrue(SceneGroups.mismatch(SceneGroups.of(other, frags), NineKeyLetters.G14) != null)
        assertEquals(twos, SceneGroups.leaningOf(NineKeyLetters.G14, other, frags))
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
