/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.view.View
import com.google.android.material.chip.Chip
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
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
 * The 注音 keyboards' swipe shortcuts (051-c 【待定 9】 A): 大千's keys take the full keyboard's six by
 * the QWERTY key each sits on, 注音九键's groups none until one is set on the page's own tab.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class ZhuyinShortcutsUatTest {

    private var made: KeyboardHarness? = null
    private var settings: SettingsHarness? = null

    @get:Rule
    val prefs = PrefsRule()

    @After
    fun close() {
        made?.close()
        settings?.close()
    }

    // the 简繁 hint not said, its "told" put back after (KeyboardPickerUatTest reads it)
    private fun harness(keyboard: ImKeyboard): KeyboardHarness {
        prefs.set(AppPrefs.getInstance().internal.zhuyinTraditionalTold, true)
        return KeyboardHarness(ThemePreset.PixelLight, keyboards = ImKeyboards.Chosen(pinyin = keyboard)).also { made = it }.start()
    }

    @Test
    @Uat("PY-112")
    fun dachensKeysTakeTheFullKeyboardsShortcutsByPlace() {
        val h = harness(ImKeyboard.ZHUYIN_FULL)
        h.field.setText("one two")
        h.idle()
        // ㄇ on a: select all, its icon drawn; ㄋ on s: none
        val m = h.key("ㄇ")
        assertEquals(1, m.drawnIcons)
        assertEquals(0, h.key("ㄋ").drawnIcons)
        for (face in listOf("ㄏ", "ㄌ", "ㄒ", "ㄈ", "ㄗ")) assertEquals(face, 1, h.key(face).drawnIcons)
        // at the standard height only symbols whose ink meets their icon's give way: the icon into
        // its corner first (ㄇ's and ㄈ's enough), then the symbol down a little (ㄌ under the
        // scissors the most), none smaller; ㄋ, no icon, and the rest as they were: told by the
        // symbol's top against its row's highest, whichever draws the keys
        val symbols = h.keys().filter { k -> k.label.singleOrNull()?.let { it in ZhuyinKeys.SYMBOLS || it in ZhuyinKeys.MARKS } == true }
        val size = symbols.map { it.textSize(it.label) }.toSet()
        assertEquals(size.toString(), 1, size.size)
        val moved = symbols.groupBy { it.bounds.top }.values.flatMap { row ->
            val top = row.minOf { it.drawn(it.label)!!.top }
            row.map { it to it.drawn(it.label)!!.top - top }.filter { it.second > 0.5f }
        }
        val yielding = setOf("ㄌ", "ㄏ", "ㄒ", "ㄗ")
        val canvas = KeyboardHarness.all(h.keyboard).any { it is KeyboardCanvasView && it.visibility == View.VISIBLE }
        if (canvas) {
            // the scene's symbols are its main characters: never moved off their row's baseline, the icons give
            // way in their corners (060-render-p2 S4e)
            assertTrue(moved.toString(), moved.isEmpty())
        } else {
            assertEquals(yielding, moved.map { it.first.label }.toSet())
        }
        for ((k, down) in moved) assertTrue("${k.label} $down", down < k.height / 4f)
        // none sideways: each symbol as far in from its key's left as on the keys with no shortcut (below), the
        // scene's text at its full width; the scene's told by its centre, its class a size for all (S4e), within a px
        val inFrom = symbols.associate { it.label to it.drawn(it.label)!!.let { r -> if (canvas) r.centerX() else r.left } - it.bounds.left }
        if (canvas) {
            for (k in symbols) assertEquals(k.label, 1.0, (k as SceneShownKey).node.ops(false).filterIsInstance<Op.Text>().single { it.text == k.label }.scaleX, 0.0)
        }
        h.captureBoard("ZhuyinShortcutsUatTest/dachen")
        h.swipe(m, 0f, -m.height * 1.3f)
        h.idle()
        assertEquals(0 to 7, h.field.selectionStart to h.field.selectionEnd)
        // ㄋ has none: up types it, as a tap
        val n = h.key("ㄋ")
        val typed = h.sent().size
        h.swipe(n, 0f, -n.height * 1.3f)
        h.idle()
        assertEquals(h.api.calls.toString(), typed + 1, h.sent().size)
        assertTrue(h.sent().last(), h.sent().last().startsWith("sendKey(sym=${UNICODE + Keyboard.zhuyinKey('ㄋ')},"))
        assertEquals(0 to 7, h.field.selectionStart to h.field.selectionEnd)
        h.close()
        // none on 大千's letters' places, their presets too
        prefs.set(AppPrefs.getInstance().internal.keyShortcuts, KeyShortcuts.Empty.cleared(('a'..'z').map { "zyfull/$it" }).serialize())
        val bare = harness(ImKeyboard.ZHUYIN_FULL)
        assertEquals(0, bare.key("ㄇ").drawnIcons)
        val bareFrom = bare.keys().filter { k -> k.label in inFrom }
            .associate { it.label to it.drawn(it.label)!!.let { r -> if (canvas) r.centerX() else r.left } - it.bounds.left }
        assertEquals(inFrom.keys, bareFrom.keys)
        for ((label, x) in inFrom) assertEquals(label, bareFrom.getValue(label), x, if (canvas) 1f else 0.01f)
    }

    private fun KeyboardHarness.sent() = api.calls.filter { it.startsWith("sendKey") && "up=false" in it }

    @Test
    @Uat("T9-61")
    fun zhuyinNinesGroupsHaveNoneUntilOneIsSet() {
        var h = harness(ImKeyboard.ZHUYIN_NINE)
        // no group's, nor a tone's or the slot's: nothing but their own faces (⌫'s icons its own shortcuts)
        val bare = h.keys().filter { it.id?.matches(Regex("r[1-4]c[1-4]")) == true }
        assertEquals(16, bare.size)
        for (k in bare) assertEquals(k.id, 0 to listOf(k.label), k.drawnIcons to k.drawnTexts)
        h.close()
        prefs.set(AppPrefs.getInstance().internal.keyShortcuts, KeyShortcuts.Empty.with("zy9/1", KeyShortcut.Text("你好")).serialize())
        h = harness(ImKeyboard.ZHUYIN_NINE)
        assertTrue(h.texts().toString(), "你好" in h.texts())
        assertEquals(listOf("ㄅㄉㄚ", "你好"), h.key("ㄅㄉㄚ").drawnTexts)
        h.captureBoard("ZhuyinShortcutsUatTest/nine_text")
        val group = h.key("ㄅㄉㄚ")
        h.swipe(group, 0f, -group.height * 1.3f)
        h.idle()
        assertEquals("你好", h.field.text.toString())
    }

    @Test
    @Uat("T9-61")
    fun thePageHasZhuyinNinesTabBeforeDachens() {
        val st = SettingsHarness().also { settings = it }
        st.open(SettingsRoute.PopupOverrides)
        val chips = KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<Chip>().map { it.text.toString() }.toList()
        val nine = chips.indexOf(st.s(R.string.shortcut_page_zy9))
        assertTrue(chips.toString(), nine >= 0 && chips.getOrNull(nine + 1) == st.s(R.string.shortcut_page_zyfull))
        KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<Chip>().first { it.text.toString() == chips[nine] }.performClick()
        st.idle()
        val texts = st.texts()
        for (g in listOf("ㄅㄉㄚ", "ㄓㄗㄢㄦ", "ㄖㄥㄩ")) assertTrue("$g in $texts", g in texts)
        // no presets to put back; the hint of changing a key's shortcut alone, not its long press
        assertTrue(texts.toString(), st.s(R.string.shortcut_apply_presets) !in texts && st.s(R.string.shortcut_clear_page) in texts)
        assertTrue(texts.toString(), st.s(R.string.shortcut_hint_page) in texts)
        st.capture("ZhuyinShortcutsUatTest/page_zy9")
    }
}
