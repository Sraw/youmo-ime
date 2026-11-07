/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.LayoutSource
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.scene.SceneAction
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The app's side of a layout's words (060-render-p2 U4 B): the `action:` table ([SceneKeyDef.action]),
 * a key's KeyDef made of its node with the keyboard's own words ([SceneKeyDef.Table]), and where the
 * layouts are read from ([SceneLayouts]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class SceneKeyDefTest {

    @get:Rule
    val prefs = PrefsRule()

    private val context get() = RuntimeEnvironment.getApplication()

    private fun sym(code: Int) = KeyAction.SymAction(KeySym(code), KeyStates.Virtual)

    @Test
    fun eachWordIsTheActionTheOldKeysHad() {
        val placeholders = mapOf("zhuyin" to KeyAction.CommitAction("ㄅ"))
        val words = mapOf(
            SceneAction.Key("'") to KeyAction.HostKeyAction("'"),
            SceneAction.Sym("BackSpace") to sym(FcitxKeyMapping.FcitxKey_BackSpace),
            SceneAction.Sym("Return") to sym(FcitxKeyMapping.FcitxKey_Return),
            SceneAction.Sym("KP_Add") to sym(FcitxKeyMapping.FcitxKey_KP_Add),
            SceneAction.Layout("Symbol") to KeyAction.LayoutSwitchAction("Symbol"),
            SceneAction.Picker(null) to KeyAction.PickerSwitchAction(),
            SceneAction.Picker("Emoji") to KeyAction.PickerSwitchAction(PickerWindow.Key.Emoji),
            SceneAction.Commit(".com") to KeyAction.CommitAction(".com"),
            SceneAction.Code(0xFF0C) to sym(UNICODE + 0xFF0C),
            SceneAction.Keypad(7) to sym(FcitxKeyMapping.FcitxKey_KP_0 + 7),
            SceneAction.Caps(true) to KeyAction.CapsAction(true),
            SceneAction.Shortcut(KeyCommand.entries.first().key) to KeyAction.ShortcutAction(KeyShortcut.Command(KeyCommand.entries.first())),
            SceneAction.Reading(null) to KeyAction.ReadingAction(),
            SceneAction.Reading(ReadingStyle.entries.first().key) to KeyAction.ReadingAction(ReadingStyle.entries.first()),
            SceneAction.EmojiSearch("on") to KeyAction.EmojiSearchAction(true),
            SceneAction.EmojiSearch("off") to KeyAction.EmojiSearchAction(false),
            SceneAction.Command("lang") to KeyAction.LangSwitchAction,
            SceneAction.Command("ime-picker") to KeyAction.ShowInputMethodPickerAction,
            SceneAction.Command("space-long-press") to KeyAction.SpaceLongPressAction(),
            SceneAction.Command("quickphrase") to KeyAction.QuickPhraseAction,
            SceneAction.Command("unicode") to KeyAction.UnicodeAction,
            SceneAction.Placeholder("zhuyin") to KeyAction.CommitAction("ㄅ"),
        )
        words.forEach { (word, action) -> assertEquals("$word", action, SceneKeyDef.action(word) { placeholders[it] }) }
        // what the app has none of: nothing (logged), the key doing nothing; a group's code is the keyboard's (Table.group)
        val none = listOf(
            SceneAction.Sym("Hyper_L"), SceneAction.Picker("Nope"), SceneAction.Shortcut("nope"),
            SceneAction.Reading("nope"), SceneAction.Command("launch"), SceneAction.Placeholder("tone-9"),
            SceneAction.Group("qw"),
        )
        none.forEach { assertNull("$it", SceneKeyDef.action(it) { name -> placeholders[name] }) }
    }

    // the 14 keys' nodes, from the canvas a GroupedKeyboard draws with the switch on
    private fun nodes(): Map<String, KeyNode> {
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, true)
        val keyboard = GroupedKeyboard(context, ThemePreset.PixelLight).laidOut()
        val canvas = KeyboardHarness.all(keyboard).filterIsInstance<KeyboardCanvasView>().single()
        return canvas.scene!!.keys.associateBy { it.id }
    }

    // a group's tap and long press asked of the keyboard by its letters; none it has: nothing wired
    @Test
    fun aGroupsTapAndLongPressAreTheKeyboards() {
        val qw = nodes().getValue("r1c1")
        val asked = mutableListOf<String>()
        val code = sym(UNICODE + 0xF8DA)
        val long = KeyDef.Popup.Keyboard.Explicit(arrayOf("q", "w"))
        val table = object : SceneKeyDef.Table {
            override fun group(letters: String, node: KeyNode) = code.also { asked += "group $letters ${node.id}" }
            override fun groupPopup(letters: String, node: KeyNode) = long.also { asked += "popup $letters ${node.id}" }
        }
        val def = SceneKeyDef.of(qw, table)
        assertEquals(listOf("group qw r1c1", "popup qw r1c1"), asked)
        assertEquals(code, def.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action)
        assertEquals(sym(FcitxKeyMapping.FcitxKey_KP_0 + 1), def.behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single().action)
        assertTrue(long in def.popup.orEmpty())
        val preview = def.popup.orEmpty().filterIsInstance<KeyDef.Popup.AltPreview>().single()
        assertEquals("QW" to "1", preview.content to preview.alternative)
        assertEquals("g14/qw", def.shortcut)
        // a keyboard with no groups: no tap, no long press, the rest as written
        val bare = SceneKeyDef.of(qw, object : SceneKeyDef.Table {})
        assertTrue(bare.behaviors.none { it is KeyDef.Behavior.Press })
        assertTrue(bare.popup.orEmpty().none { it is KeyDef.Popup.Keyboard })
        assertNotNull(bare.behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().singleOrNull())
    }

    @Test
    fun theBottomRowsKeysAreWiredAsTheirViewsWere() {
        val k = nodes().mapValues { SceneKeyDef.of(it.value, object : SceneKeyDef.Table {}) }
        val space = k.getValue("space")
        assertTrue(space.isSpace && space.swipesBothWays)
        assertEquals(org.fcitx.fcitx5.android.data.InputFeedbacks.SoundEffect.SpaceBar, space.sound)
        val backspace = k.getValue("backspace")
        assertTrue(backspace.isBackspace)
        assertEquals(sym(FcitxKeyMapping.FcitxKey_BackSpace), backspace.behaviors.filterIsInstance<KeyDef.Behavior.Repeat>().single().action)
        val enter = k.getValue("enter")
        assertEquals(org.fcitx.fcitx5.android.data.InputFeedbacks.SoundEffect.Return, enter.sound)
        val menu = enter.popup.orEmpty().filterIsInstance<KeyDef.Popup.Menu>().single()
        assertEquals(listOf(KeyAction.PickerSwitchAction()), menu.items.map { it.action })
        assertEquals(KeyAction.HostKeyAction("'"), k.getValue("apostrophe").behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action)
    }

    // the layouts are read once a process from their source; one that does not read is none (the views draw)
    @Test
    fun aLayoutReadOnceAndOneThatDoesNotReadIsNone() {
        val builtIn = SceneLayouts.source
        try {
            for (id in listOf("qwerty", "k14", "k18")) assertNotNull(id, SceneLayouts.of(context, id))
            assertEquals(setOf("grouped-bottom"), SceneLayouts.of(context, "k14")!!.fragments.keys)
            val read = mutableListOf<String>()
            SceneLayouts.source = { LayoutSource { id -> read += id; if (id == "k14") "<keyboard/>" else null } }
            assertNull(SceneLayouts.of(context, "k14"))
            assertNull(SceneLayouts.of(context, "k14"))
            assertNull(SceneLayouts.of(context, "nine"))
            assertEquals(listOf("k14", "nine"), read)
            // a source that throws: none, too
            SceneLayouts.source = { LayoutSource { error("no file") } }
            assertNull(SceneLayouts.of(context, "k18"))
            // a keyboard whose layout did not read is drawn by its views
            prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, true)
            val keyboard = GroupedKeyboard(context, ThemePreset.PixelLight).laidOut()
            assertTrue(KeyboardHarness.all(keyboard).none { it is KeyboardCanvasView })
        } finally {
            SceneLayouts.source = builtIn
        }
    }
}
