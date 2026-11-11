/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.engine.table.TableKeyboard
import org.fcitx.fcitx5.android.engine.table.TableRoots
import org.fcitx.fcitx5.android.input.keyboard.AddressKeys
import org.fcitx.fcitx5.android.input.keyboard.KeyMarks
import org.fcitx.fcitx5.android.input.keyboard.KeySwipe
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.engine.MarkSettings
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.layout.Include
import org.fcitx.fcitx5.android.scene.layout.Key
import org.fcitx.fcitx5.android.scene.layout.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.fcitx.fcitx5.android.scene.rules.KeyMarks as SceneKeyMarks

class TextBoardSceneTest {

    private val theme = SceneData.theme("material-light.xml")

    // the keys qwerty.xml shows for [state], in order
    private fun keys(state: TextBoardState, split: Boolean = false): List<String> {
        val env = SceneData.portrait.copy(input = state.input(), split = split)
        val scene = (SceneBuilder(theme, env, SceneData.measurer).build(SceneData.qwerty, emptyMap()) as Parsed.Ok).value
        return scene.keys.map { it.id }.distinct()
    }

    @Test
    fun aPlainFieldHasShiftReturnTheCommaAndTheLanguageKey() {
        val k = keys(TextBoardState())
        for (id in listOf("shift", "enter", "comma", "period", "lang", "space", "backspace", "symbols", "numbers")) assertTrue(id, id in k)
        for (id in listOf("semicolon", "star", "lookup", "lookup-on", "confirm", "slash", "at", "dot", "domain")) assertFalse(id, id in k)
        assertEquals(26, k.count { it.length == 1 })
    }

    @Test
    fun shuangpinsSemicolonUnlessSplit() {
        assertTrue("semicolon" in keys(TextBoardState(semicolonScheme = true)))
        assertFalse("semicolon" in keys(TextBoardState(semicolonScheme = true), split = true))
        assertEquals("shuangpin", TextBoardState(semicolonScheme = true).input().ime)
    }

    @Test
    fun aTablesKeys() {
        val looking = keys(TextBoardState(table = true, lookingUp = true, tableExtra = '*'))
        assertTrue("lookup-on" in looking && "star" in looking)
        assertFalse("shift" in looking || "lookup" in looking)
        val typing = keys(TextBoardState(table = true, composing = true, tableExtra = ';'))
        assertTrue("lookup" in typing && "confirm" in typing && "semicolon" in typing)
        assertFalse("enter" in typing)
        // composing elsewhere keeps Return
        assertTrue("enter" in keys(TextBoardState(composing = true)))
        // and is no other environment: the keyboard keeps the scene it had (TextKeyboard.scene)
        assertEquals(TextBoardState(semicolonScheme = true).input(), TextBoardState(semicolonScheme = true, composing = true).input())
        assertNull(TextBoardState(tableExtra = 'x').input().tableExtra)
    }

    // why input() may drop composing without a table: qwerty.xml asks it nowhere else. Every when= in
    // it, every other term either way, holds alike composing or not once there is no table
    @Test
    fun qwertyAsksComposingOnlyOfATable() {
        val layout = SceneData.qwerty
        val conditions = layout.parts.flatMap { part ->
            when (part) {
                is Row -> listOfNotNull(part.condition) + part.cells.flatMap { cell ->
                    listOfNotNull(cell.condition) + ((cell as? Key)?.variants?.map { it.condition } ?: emptyList())
                }
                is Include -> listOfNotNull(part.condition)
            }
        }
        // an include's own parts are not walked here: there are none to miss
        assertTrue("qwerty.xml includes nothing", layout.parts.none { it is Include })
        val asking = conditions.filter { c -> c.anyOf.flatten().any { it.name == "composing" } }
        assertTrue("qwerty.xml asks composing somewhere", asking.isNotEmpty())
        for (c in asking) {
            val others = c.anyOf.flatten().filter { it.name != "composing" && it.name != "table" }.map { it.copy(negated = false) }.distinct()
            for (set in 0 until (1 shl others.size)) {
                fun holds(composing: Boolean) = c.holds { name, values ->
                    when (name) {
                        "composing" -> composing
                        "table" -> false
                        else -> others.indexOfFirst { it.name == name && it.values == values }.let { set and (1 shl it) != 0 }
                    }
                }
                assertEquals("$c", holds(false), holds(true))
            }
        }
    }

    // a table's roots over the letters: `when=roots` takes `{root:x}`, the corners the letters
    @Test
    fun aTablesRootsOverTheLetters() {
        val wubi = TableKeyboard(InputMethodCatalog.WUBI, emptySet(), false, KeyLabels.ROOTS, TableRoots.WUBI86)
        val roots = SceneLetters.roots(wubi)
        assertEquals(26, roots.size)
        assertEquals(wubi.label('g'), roots["root:g"])
        assertTrue(roots.values.any { '\n' in it })
        // z has no root: left empty, the engine's {root:z} shows the letter ({case:z})
        assertEquals("", roots["root:z"])
        // none shown: nothing for the binding, nor while the table shows the letters
        assertTrue(SceneLetters.roots(null).isEmpty())
        assertTrue(SceneLetters.roots(wubi.copy(labels = KeyLabels.NONE)).isEmpty())
        val alts = SceneLetters.alts { c -> if (c == 'q') null else "$c!" }
        assertEquals(25, alts.size)
        assertEquals("a!", alts["a"])
        val state = TextBoardState(table = true, roots = true)
        assertTrue(state.input().roots)
        val env = SceneData.portrait.copy(input = state.input(), texts = SceneData.portrait.texts.copy(alts = SceneLetters.alts { "$it" }, other = roots))
        val g = (SceneBuilder(theme, env, SceneData.measurer).build(SceneData.qwerty, emptyMap()) as Parsed.Ok).value.keys.first { it.id == "g" }
        assertEquals(wubi.label('g'), g.label)
        assertEquals("g", g.alt)
    }

    @Test
    fun anAddressFieldsKeys() {
        val url = keys(TextBoardState(address = AddressKeys.URL))
        assertTrue(listOf("slash", "dot", "domain").all { it in url })
        assertFalse("comma" in url || "period" in url || "at" in url)
        val email = keys(TextBoardState(address = AddressKeys.EMAIL, langKey = false))
        assertTrue("at" in email && "lang" !in email)
    }

    @Test
    fun holdingTheSpaceToTalk() {
        val input = TextBoardState(holdVoice = true).input()
        assertTrue(input.holdVoice && input.voice)
    }

    @Test
    fun theMarksAsTheSettingsAre() {
        val slots = SwipeSlots.Default
            .with("full/q", up = false, SwipeSlot.Text("hello"))
            .with("full/a", up = false, SwipeSlot.Empty)
            .with("full/s", up = true, SwipeSlot.Command(KeyCommand.Paste))
            .with("fn/space", up = false, SwipeSlot.Command(KeyCommand.Clipboard))
            .with("fn/comma", up = true, SwipeSlot.First)
            .with("fn/period", up = true, SwipeSlot.First)
            .with("t9/2", up = true, SwipeSlot.First)
        val names = SceneMarks.names(SceneData.qwerty) + setOf("t9/2", "t9/3", "period")
        assertTrue("full/q" in names && "space" in names && "return" in names)
        val firsts = mapOf("comma" to "!", "t9/3" to null)
        val m = SceneMarks.settings(slots, KeySwipe.Ways.Both, KeyMarks.At.BottomLeft, KeyMarks.At.Bottom, true, names, firsts)
        assertEquals("two characters, then an ellipsis", MarkContent.Text("he…", "h…"), m.shortcuts["full/q:down"])
        assertEquals(MarkContent.Corner, m.shortcuts["full/q:up"])
        assertEquals(MarkContent.Empty, m.shortcuts["full/a:down"])
        assertEquals(MarkContent.Icon("content-paste"), m.shortcuts["full/s:up"])
        assertEquals(MarkContent.Icon("content-copy"), m.shortcuts["full/c:down"])
        assertEquals(MarkContent.Icon("cursor-move"), m.shortcuts["space:up"])
        assertEquals(MarkContent.Icon("clipboard"), m.shortcuts["space:down"])
        assertEquals(MarkContent.Icon("delete-sweep"), m.shortcuts["backspace:up"])
        assertEquals("a function key's first: its long press's", MarkContent.Symbol("!"), m.shortcuts["comma:up"])
        assertEquals("none known: nothing", MarkContent.Empty, m.shortcuts["period:up"])
        assertEquals("a long press turned off", MarkContent.Empty, m.shortcuts["t9/3:up"])
        assertEquals(MarkContent.Corner, m.shortcuts["t9/2:up"])
        assertEquals(SceneKeyMarks.At.BottomLeft, m.upAt)
        assertEquals(SceneKeyMarks.At.Bottom, m.downAt)
        assertTrue(m.charShortcuts && m.upOn && m.downOn && m.firstUp)
        // the built-in ones as the engine's own preset names them
        val preset = SceneMarks.settings(SwipeSlots.Default, KeySwipe.Ways.Both, KeyMarks.At.TopRight, KeyMarks.At.TopLeft, true, names)
        MarkSettings.PRESET.forEach { (k, v) -> assertEquals(k, v, preset.shortcuts[k]) }
        val flipped = SceneMarks.settings(SwipeSlots.Default.flippedAll(listOf("fn/space")), KeySwipe.Ways.Both, KeyMarks.At.TopRight, KeyMarks.At.TopLeft, true, names)
        assertFalse(flipped.firstUp)
        assertEquals(MarkContent.Icon("select-all"), flipped.shortcuts["full/a:up"])
        assertEquals(MarkContent.Icon("cursor-move"), flipped.shortcuts["space:down"])
    }

    @Test
    fun theWaysOnAndTheRoomForShortcuts() {
        val off = SceneMarks.settings(SwipeSlots.Default, KeySwipe.Ways(up = true, down = false), KeyMarks.At.TopRight, KeyMarks.At.TopLeft, true, setOf("full/a"))
        assertTrue(off.upOn)
        assertFalse(off.downOn)
        // a table's roots take the room
        assertFalse(SceneMarks.settings(SwipeSlots.Default, KeySwipe.Ways.Both, KeyMarks.At.TopRight, KeyMarks.At.TopLeft, false, setOf("full/a")).charShortcuts)
    }

    @Test
    fun everyCommandHasAnIconName() {
        val names = KeyCommand.entries.map(SceneMarks::icon)
        assertEquals(names.size, names.toSet().size)
        assertEquals("hide-keyboard", SceneMarks.icon(KeyCommand.HideKeyboard))
        assertEquals("undo", SceneMarks.icon(KeyCommand.Undo))
    }
}
