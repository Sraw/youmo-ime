/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.engine.table.TableKeyboard
import org.fcitx.fcitx5.android.engine.table.TableRoots
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The 26 keys drawn by the scene, wired from qwerty.xml's nodes (060-render-p2 U4 B), do what the
 * views' KeyDef (TextKeyboard.Layout) says, key by key, in each of its forms: pinyin, English, 双拼 with
 * `;`, a table's (五笔 with its roots and 拼, 仓颉 with its `*`), an address field's keys. A letter's
 * press and long press are its letter in either case (TextKeyboard sends and shows them as Shift has
 * them); its bubble its letter too, whatever its face (a table's roots: TextKeyboard.sceneBubble).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = KeyboardCanvasViewTest.PORTRAIT)
class TextSceneWiringTest {

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun reset() {
        EngineState.tableKeyboardState.value = null
        EngineState.shuangpinScheme = ShuangpinScheme.XIAOHE
    }

    private fun entry(im: String, name: String) = InputMethodEntry(im, name, "", "", name.take(1), "zh_CN", "androidengine", false)

    private fun compare(form: String, im: InputMethodEntry, table: TableKeyboard? = null, inputType: Int = InputType.TYPE_CLASS_TEXT, composing: Boolean = false): Set<String> {
        prefs.set(AppPrefs.getInstance().internal.canvasKeyboard, true)
        return KeyboardHarness(im = im).start(inputType).use { h ->
            EngineState.tableKeyboardState.value = table
            if (composing) EngineState.syllablesState.value = EngineState.SyllableState(im.uniqueName, true, listOf("a"), 1)
            h.idle()
            h.relayout()
            val k = KeyboardHarness.all(h.keyboard).filterIsInstance<TextKeyboard>().single()
            val scene = KeyboardHarness.all(k).filterIsInstance<KeyboardCanvasView>().single().scene!!
            val old = TextKeyboard.Layout.flatten().associateBy { it.name }
            for (node in scene.keys) {
                val was = old[node.id] ?: error("$form ${node.id}: no view's key")
                val now = k.sceneKey(node)!!.def
                val at = "$form ${node.id}"
                val letter = node.id.length == 1 && node.id[0] in 'a'..'z'
                assertEquals(at, was.shortcut, now.shortcut)
                assertEquals(at, was.swipesBothWays, now.swipesBothWays)
                assertEquals(at, actions(was, letter), actions(now, letter))
                assertEquals(at, popups(was, letter), popups(now, letter))
            }
            EngineState.syllablesState.value = EngineState.SyllableState()
            scene.keys.map { it.id }.toSet()
        }
    }

    @Test
    fun pinyinsKeys() {
        val ids = compare("pinyin", KeyboardHarness.PINYIN)
        assertTrue(ids.toString(), ids.containsAll(listOf("q", "m", "shift", "comma", "period", "enter")))
    }

    @Test
    fun englishsKeys() {
        compare("english", KeyboardHarness.ENGLISH)
    }

    @Test
    fun shuangpinsKeysWithItsSemicolon() {
        EngineState.shuangpinScheme = ShuangpinScheme.MICROSOFT
        val ids = compare("shuangpin", entry(InputMethodCatalog.SHUANGPIN, "双拼"))
        assertTrue(ids.toString(), "semicolon" in ids)
    }

    @Test
    fun wubisKeysWithItsRootsAndLookUp() {
        val ids = compare("wubi", entry(InputMethodCatalog.WUBI, "五笔"), TableKeyboard(InputMethodCatalog.WUBI, emptySet(), false, KeyLabels.NAMES, TableRoots.WUBI86))
        assertTrue(ids.toString(), "lookup" in ids)
        val on = compare("wubi on", entry(InputMethodCatalog.WUBI, "五笔"), TableKeyboard(InputMethodCatalog.WUBI, emptySet(), true, KeyLabels.NAMES, TableRoots.WUBI86), composing = true)
        assertTrue(on.toString(), "lookup-on" in on)
    }

    @Test
    fun cangjiesKeysWithItsStar() {
        val ids = compare("cangjie", entry(InputMethodCatalog.CANGJIE, "仓颉"), TableKeyboard(InputMethodCatalog.CANGJIE, setOf('*'), false, KeyLabels.NAMES, TableRoots.CANGJIE))
        assertTrue(ids.toString(), "star" in ids)
    }

    @Test
    fun anAddressFieldsKeys() {
        val url = compare("url", KeyboardHarness.PINYIN, inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        assertTrue(url.toString(), url.containsAll(listOf("slash", "dot", "domain")))
        val email = compare("email", KeyboardHarness.ENGLISH, inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        assertTrue(email.toString(), "at" in email)
    }

    // a letter's in either case: TextKeyboard sends it as Shift has it
    private fun actions(def: KeyDef, letter: Boolean) = def.behaviors.map {
        val a = when (it) {
            is KeyDef.Behavior.Press -> it.action
            is KeyDef.Behavior.LongPress -> it.action
            is KeyDef.Behavior.Repeat -> it.action
            is KeyDef.Behavior.Swipe -> it.action
            is KeyDef.Behavior.DoubleTap -> it.action
        }
        "${it::class.simpleName} ${if (letter && a is KeyAction.HostKeyAction) a.copy(act = a.act.lowercase()) else a}"
    }.toSet()

    private fun popups(def: KeyDef, letter: Boolean) = def.popup.orEmpty().map { p ->
        when (p) {
            // a letter's bubble shows its letter as Shift has it (sceneBubble, transformAlphabet)
            is KeyDef.Popup.AltPreview -> "bubble ${if (letter) "letter" else p.content} ${p.alternative}"
            is KeyDef.Popup.Preview -> "bubble ${p.content}"
            is KeyDef.Popup.Keyboard.Explicit -> "keys ${p.items.toList()} ${p.symbolsFrom} ${p.asIs} ${p.inRow}"
            is KeyDef.Popup.Keyboard.Preset -> "preset ${if (letter) p.label.lowercase() else p.label} ${p.transformPunctuation}"
            is KeyDef.Popup.Menu -> "menu " + p.items.map { "${it.label} ${it.icon} ${it.action}" }
            else -> "$p"
        }
    }.toSet()
}
