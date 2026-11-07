/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

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
        assertNull(TextBoardState(tableExtra = 'x').input().tableExtra)
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

    @Test
    fun spokenWordsTheAppsStringsWhereNamed() {
        val strings = mapOf("a11y_key_shift" to "Shift")
        assertEquals("Shift", SceneA11y.spoken("a11y_key_shift", null, strings::get))
        assertEquals("⇧", SceneA11y.spoken("a11y_key_unknown", "⇧", strings::get))
        assertEquals("a11y_key_unknown", SceneA11y.spoken("a11y_key_unknown", null, strings::get))
        assertEquals("q，1", SceneA11y.spoken("q，1", "q", strings::get))
        assertEquals("q", SceneA11y.spoken("", "q", strings::get))
        assertEquals("", SceneA11y.spoken("", null, strings::get))
    }
}
