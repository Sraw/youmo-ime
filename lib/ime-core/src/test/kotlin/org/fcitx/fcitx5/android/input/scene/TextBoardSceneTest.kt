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
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayers
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
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
        val shortcuts = KeyShortcuts.Empty
            .with("full/q", KeyShortcut.Text("hi"))
            .with("full/a", KeyShortcut.None)
            .with(KeyShortcuts.functionKey("space", up = false), KeyShortcut.Command(KeyCommand.Clipboard))
        val names = SceneMarks.names(SceneData.qwerty)
        assertTrue("full/q" in names && "space" in names && "return" in names)
        val m = SceneMarks.settings(shortcuts, SwipeLayers.Default, KeyMarks.Spots(KeyMarks.At.BottomLeft, KeyMarks.At.Bottom), true, names)
        assertEquals(MarkContent.Text("hi"), m.shortcuts["full/q"])
        assertNull(m.shortcuts["full/a"])
        assertEquals(MarkContent.Icon("content-copy"), m.shortcuts["full/c"])
        assertEquals(MarkContent.Icon("cursor-move"), m.shortcuts["space:up"])
        assertEquals(MarkContent.Icon("clipboard"), m.shortcuts["space:down"])
        assertEquals(MarkContent.Icon("delete-sweep"), m.shortcuts["backspace:up"])
        assertEquals(SceneKeyMarks.At.BottomLeft, m.symbolAt)
        assertEquals(SceneKeyMarks.At.Bottom, m.shortcutAt)
        assertTrue(m.charShortcuts && m.upOn && m.downOn)
        // the built-in ones as the engine's own preset names them
        val preset = SceneMarks.settings(KeyShortcuts.Empty, SwipeLayers.Default, KeyMarks.Spots(KeyMarks.At.TopRight, KeyMarks.At.TopLeft), true, names)
        MarkSettings.PRESET.forEach { (k, v) -> assertEquals(k, v, preset.shortcuts[k]) }
    }

    @Test
    fun noShortcutLayerNoCharacterShortcuts() {
        val spots = KeyMarks.Spots(KeyMarks.At.TopRight, KeyMarks.At.TopLeft)
        val off = SceneMarks.settings(KeyShortcuts.Empty, SwipeLayers(SwipeLayers.Layer.Symbol, SwipeLayers.Layer.Off), spots, true, setOf("full/a"))
        assertFalse(off.charShortcuts)
        assertTrue(off.upOn)
        assertFalse(off.downOn)
        // a table's roots take the room
        assertFalse(SceneMarks.settings(KeyShortcuts.Empty, SwipeLayers.Default, spots, false, setOf("full/a")).charShortcuts)
    }

    @Test
    fun everyCommandHasAnIconName() {
        val names = KeyCommand.entries.map(SceneMarks::icon)
        assertEquals(names.size, names.toSet().size)
        assertEquals("hide-keyboard", SceneMarks.icon(KeyCommand.HideKeyboard))
        assertEquals("undo", SceneMarks.icon(KeyCommand.Undo))
    }
}
