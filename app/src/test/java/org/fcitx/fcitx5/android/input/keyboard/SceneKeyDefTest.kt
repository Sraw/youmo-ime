/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
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

    // a source of layouts that counts what it is asked for: [text] its layouts, [key] what they are kept under
    private class Counted(override val key: String, val text: (String) -> String?) : LayoutSource {
        val asked = mutableListOf<String>()

        override fun text(id: String): String? = text.invoke(id).also { asked += id }
    }

    private fun sources(source: LayoutSource, fallback: LayoutSource, check: () -> Unit) {
        val (s, f) = SceneLayouts.source to SceneLayouts.fallback
        try {
            SceneLayouts.source = { source }
            SceneLayouts.fallback = { fallback }
            check()
        } finally {
            SceneLayouts.source = s
            SceneLayouts.fallback = f
        }
    }

    // the layouts are read once a process from each source, by the source's key and the layout's id; one
    // the source has none of, or that does not read, is the app's own (one step back); neither: none
    @Test
    fun aLayoutReadOnceFromEachSourceTheAppsOwnWhereTheSourcesDoesNotRead() {
        for (id in listOf("qwerty", "k14", "k18")) assertNotNull(id, SceneLayouts.of(context, id))
        assertEquals(setOf("grouped-bottom"), SceneLayouts.of(context, "k14")!!.fragments.keys)
        val builtIn = BuiltInLayouts(context)
        val app = Counted(BuiltInLayouts.KEY, builtIn::text)
        val pack = Counted("pack") { id -> if (id == "k14") "<keyboard/>" else builtIn.text(id) }
        sources(pack, app) {
            // the pack's k14 does not read: the app's, read once each
            val k14 = SceneLayouts.of(context, "k14")!!
            assertTrue(k14.layout.parts.isNotEmpty())
            assertEquals(k14, SceneLayouts.of(context, "k14"))
            assertEquals(listOf("k14"), pack.asked)
            assertEquals(listOf("k14", "grouped-bottom"), app.asked)
            // the pack's k18: its own
            assertNotNull(SceneLayouts.of(context, "k18"))
            assertEquals(listOf("k14", "k18", "grouped-bottom"), pack.asked)
            assertEquals(listOf("k14", "grouped-bottom"), app.asked)
            // neither has it
            assertNull(SceneLayouts.of(context, "nine"))
            assertNull(SceneLayouts.of(context, "nine"))
            assertEquals(listOf("k14", "k18", "grouped-bottom", "nine"), pack.asked)
            assertEquals(listOf("k14", "grouped-bottom", "nine"), app.asked)
            // the pack's read again, the app's kept (P3: a theme pack changed)
            SceneLayouts.invalidate("pack")
            SceneLayouts.of(context, "k14")
            SceneLayouts.of(context, "k18")
            assertEquals(listOf("k14", "k18", "grouped-bottom", "nine", "k14", "k18", "grouped-bottom"), pack.asked)
            assertEquals(listOf("k14", "grouped-bottom", "nine"), app.asked)
            // all of them read again
            SceneLayouts.invalidate()
            SceneLayouts.of(context, "k14")
            assertEquals(listOf("k14", "grouped-bottom", "nine", "k14", "grouped-bottom"), app.asked)
        }
        // a source that throws: none from it; the app's own the one step back, its own key's no step at all
        sources(LayoutSource { error("no file") }, app) { assertNotNull(SceneLayouts.of(context, "k18")) }
        sources(Counted(BuiltInLayouts.KEY) { error("no file") }, Counted(BuiltInLayouts.KEY, builtIn::text)) { assertNull(SceneLayouts.of(context, "k18")) }
        assertTrue(BuiltInLayouts.has("k14") && BuiltInLayouts.has("qwerty") && !BuiltInLayouts.has("nine"))
    }

    // a keyboard moved to the scene (the app has its layout) whose layout does not read draws nothing with
    // the canvas on: the canvas over it, not one key, no view of a key shown (U8; review S4a #1), logged once
    @Test
    fun aMovedKeyboardWhoseLayoutDoesNotReadDrawsNothing() {
        val logged = mutableListOf<String>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority == android.util.Log.ERROR) logged += message
            }
        }
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, true)
        timber.log.Timber.plant(tree)
        try {
            sources(Counted("pack") { "<keyboard/>" }, Counted(BuiltInLayouts.KEY) { "<keyboard/>" }) {
                val keyboards = listOf(
                    GroupedKeyboard(context, ThemePreset.PixelLight).laidOut(),
                    GroupedKeyboard(context, ThemePreset.PixelLight, NineKeyLetters.G18).laidOut(),
                    TextKeyboard(context, ThemePreset.PixelLight).laidOut(),
                    // made again: read once all the same
                    TextKeyboard(context, ThemePreset.PixelLight).laidOut(),
                )
                for (k in keyboards) {
                    val canvas = KeyboardHarness.all(k).filterIsInstance<KeyboardCanvasView>().single()
                    assertEquals(View.VISIBLE, canvas.visibility)
                    assertTrue(KeyboardHarness.keysOf(k).isEmpty())
                    assertTrue(KeyboardHarness.all(k).filterIsInstance<KeyView>().none { it.isShown })
                }
                // each layout's two readings, logged once a process
                assertEquals(logged.toString(), 2 * 3, logged.count { it.startsWith("scene layout") })
            }
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }

    // a keyboard not moved yet (the app has no layout of its id): its views draw, the canvas on or not
    @Test
    fun aKeyboardNotMovedIsDrawnByItsViews() {
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, true)
        val unmoved = object : BaseKeyboard(context, ThemePreset.PixelLight, emptyList()) {
            val scene = drawKeysWithScene("nine")
            val moved = drawKeysWithScene("qwerty")
        }.laidOut()
        assertEquals(false, unmoved.scene)
        assertEquals(true, unmoved.moved)
    }
}
