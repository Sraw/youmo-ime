/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.keyboard.GroupedKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.NineKeySymbols
import org.fcitx.fcitx5.android.input.keyboard.OwnLongPress
import org.fcitx.fcitx5.android.input.keyboard.T9Keyboard
import org.fcitx.fcitx5.android.input.scene.SceneBehavior
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPages
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
 * The settings editor's start for a key (OwnLongPress) is what the key's keyboard pops up, every key set by
 * name on every keyboard the tabs stand for (0.6.0 beta2: the editor showed nothing but the user's edits),
 * and a list set by name pops up in the own's shape.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class OwnLongPressUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private var made: KeyboardHarness? = null

    @After
    fun close() {
        made?.close()
        EngineState.shuangpinScheme = ShuangpinScheme.XIAOHE
    }

    private val shuangpin = InputMethodEntry(InputMethodCatalog.SHUANGPIN, "双拼", "", "", "双", "zh_CN", "androidengine", false)

    private fun harness(im: InputMethodEntry, chosen: ImKeyboards.Chosen, scheme: ShuangpinScheme = ShuangpinScheme.XIAOHE): KeyboardHarness {
        made?.close()
        EngineState.shuangpinScheme = scheme
        return KeyboardHarness(TestThemes.PixelLight, im, keyboards = chosen).start().also { made = it; it.relayout() }
    }

    // each key named for a long press by name: its own as the editor starts it, against what holding it pops up
    // (the order aside: the popup's is its places'); a corner the popup has not (九键's 1) is the editor's first only
    private fun checkEveryKey(h: KeyboardHarness) {
        val keys = h.keys().filterIsInstance<SceneShownKey>().filter { k -> k.node.shortcut?.let(KeyShortcutPages::longPressByName) == true && k.node.copy == 0 }
        assertTrue("no key named for a long press", keys.isNotEmpty())
        for (k in keys) {
            val node = k.node
            val own = OwnLongPress.of(SceneBehavior.of(node).popup, node)
            val shown = h.longPress(k)
            h.idle()
            val corner = GroupedKeyboard.cornerOf(node)
            if (corner.isNotEmpty() && own.isNotEmpty()) assertEquals(node.shortcut, corner, own.first())
            val popupOwn = if (own.isNotEmpty() && own.first() !in shown) own.drop(1) else own
            assertEquals(node.shortcut, shown.sorted(), popupOwn.sorted())
        }
    }

    @Test
    @Uat("ST-100")
    fun everyKeysEditorStartIsWhatItsKeyboardPopsUp() {
        checkEveryKey(harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14)))
        checkEveryKey(harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS18)))
        checkEveryKey(harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.NINE_KEYS)))
        checkEveryKey(harness(KeyboardHarness.ENGLISH, ImKeyboards.Chosen(english = ImKeyboard.NINE_KEYS)))
        checkEveryKey(harness(shuangpin, ImKeyboards.Chosen(shuangpin = ImKeyboard.KEYS18), ShuangpinScheme.MICROSOFT))
        checkEveryKey(harness(shuangpin, ImKeyboards.Chosen(shuangpin = ImKeyboard.NINE_KEYS)))
        checkEveryKey(harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.ZHUYIN_NINE)))
        checkEveryKey(harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.FULL)))
    }

    // 双拼 18 键 under a scheme typing `;`: L's own with its `;` (L : - l ;), as its keyboard has it
    @Test
    fun shuangpins18KeysLHasItsSemicolon() {
        val h = harness(shuangpin, ImKeyboards.Chosen(shuangpin = ImKeyboard.KEYS18), ShuangpinScheme.MICROSOFT)
        val l = h.keys().filterIsInstance<SceneShownKey>().first { it.node.shortcut == "g18/l" }
        val own = OwnLongPress.of(SceneBehavior.of(l.node).popup, l.node)
        assertTrue(own.toString(), own.containsAll(listOf("L", ":", "l", ";")))
    }

    // a list set by name: shown in the own's shape, its row the first in the middle, committed as it is where the own is
    @Test
    fun aListSetByNameKeepsTheOwnsShape() {
        val own = GroupedKeyboard.longPressOf("qw", "1") { emptyList() }
        val shaped = OwnLongPress.shaped(arrayOf("1", "Q", "W", "q", "w", "①"), own)
        assertEquals(listOf("Q", "W", "1", "q", "w", "①"), shaped.items.toList())
        assertEquals(2, shaped.inRow)
        assertEquals(5, shaped.symbolsFrom)
        // reordered: the row's run after the first, the rest above
        val moved = OwnLongPress.shaped(arrayOf("q", "①", "Q"), own)
        assertEquals(listOf("q", "①", "Q"), moved.items.toList())
        assertEquals(0, moved.inRow)
        assertEquals(1, moved.symbolsFrom)
        val one = T9Keyboard.onePopup(english = false, NineKeySymbols.STANDARD)!!
        assertTrue(OwnLongPress.shaped(arrayOf("1", "。", "～"), one).asIs)
        assertEquals(-1, OwnLongPress.shaped(arrayOf("a"), KeyDef.Popup.Keyboard.Explicit(arrayOf("b"))).inRow)
    }

    // every character deleted: the key pops up nothing, and its up swipe to the first, having none, is a tap
    // (as a faded key's is, docs/settings.md), not the corner's 1
    @Test
    @Uat("ST-100")
    fun aListEmptiedTurnsTheLongPressOff() {
        prefs.set(AppPrefs.getInstance().internal.keyPopups, "g14/qw")
        val h = harness(KeyboardHarness.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14))
        val qw = h.key("QW")
        // the key's bubble alone, no popup of characters
        assertEquals(emptyList<String>(), h.longPress(qw) - "QW")
        h.idle()
        fun sends() = h.api.calls.filter { it.startsWith("sendKey") || it.startsWith("commit") }.also { h.api.calls.clear() }
        sends()
        h.tap(qw)
        h.idle()
        val tap = sends()
        h.swipe(qw, 0f, -qw.height * 1.3f)
        h.idle()
        assertEquals(tap, sends())
        assertTrue(h.field.text.toString(), "1" !in h.field.text.toString())
    }
}
