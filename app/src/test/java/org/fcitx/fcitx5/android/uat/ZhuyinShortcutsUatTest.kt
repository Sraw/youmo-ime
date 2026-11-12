/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import com.google.android.material.chip.Chip
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keys.KeyPopup
import org.fcitx.fcitx5.android.input.keys.KeyState
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.ui.main.settings.KeyCaps
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
import org.robolectric.shadows.ShadowDialog

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
        return KeyboardHarness(TestThemes.PixelLight, keyboards = ImKeyboards.Chosen(pinyin = keyboard)).also { made = it }.start()
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
        // at the standard height only the icons give way, into their corners, where a symbol's ink
        // meets theirs (ㄇ's and ㄈ's, ㄌ under the scissors); no symbol moved off its row's baseline,
        // none smaller; ㄋ, no icon, as it was: told by the symbol's top against its row's highest
        val symbols = h.keys().filter { k -> k.label.singleOrNull()?.let { it in ZhuyinKeys.SYMBOLS || it in ZhuyinKeys.MARKS } == true }
        val size = symbols.map { it.textSize(it.label) }.toSet()
        assertEquals(size.toString(), 1, size.size)
        val moved = symbols.groupBy { it.bounds.top }.values.flatMap { row ->
            val top = row.minOf { it.drawn(it.label)!!.top }
            row.map { it to it.drawn(it.label)!!.top - top }.filter { it.second > 0.5f }
        }
        assertTrue(KeyboardHarness.all(h.keyboard).any { it is KeyboardCanvasView && it.visibility == View.VISIBLE })
        // the scene's symbols are its main characters: never moved off their row's baseline, the icons give
        // way in their corners (060-render-p2 S4e)
        assertTrue(moved.toString(), moved.isEmpty())
        // none sideways: each symbol as far in from its key's left as on the keys with no shortcut (below), the
        // scene's text at its full width. The scene's told by its middle: its class is one size for every key
        // (S4e) and may be smaller where the shortcuts are drawn than with none, which moves a glyph's left in but
        // not its middle; within a px, the scene placing its text on its edges before they are rounded
        val inFrom = symbols.associate { it.label to it.drawn(it.label)!!.centerX() - it.bounds.left }
        for (k in symbols) assertEquals(k.label, 1.0, (k as SceneShownKey).node.ops(false).filterIsInstance<Op.Text>().single { it.text == k.label }.scaleX, 0.0)
        h.captureBoard("ZhuyinShortcutsUatTest/dachen")
        h.swipe(m, 0f, m.height * 1.3f)
        h.idle()
        assertEquals(0 to 7, h.field.selectionStart to h.field.selectionEnd)
        // ㄋ has none: down types it, as a tap
        val n = h.key("ㄋ")
        val typed = h.sent().size
        h.swipe(n, 0f, n.height * 1.3f)
        h.idle()
        assertEquals(h.api.calls.toString(), typed + 1, h.sent().size)
        assertTrue(h.sent().last(), h.sent().last().startsWith("sendKey(sym=${UNICODE + Keyboard.zhuyinKey('ㄋ')},"))
        assertEquals(0 to 7, h.field.selectionStart to h.field.selectionEnd)
        h.close()
        // none on 大千's letters' places, their presets too
        prefs.setShortcuts(KeyShortcuts.Empty.cleared(('a'..'z').map { "zyfull/$it" }))
        val bare = harness(ImKeyboard.ZHUYIN_FULL)
        assertEquals(0, bare.key("ㄇ").drawnIcons)
        val bareFrom = bare.keys().filter { k -> k.label in inFrom }
            .associate { it.label to it.drawn(it.label)!!.centerX() - it.bounds.left }
        assertEquals(inFrom.keys, bareFrom.keys)
        for ((label, x) in inFrom) assertEquals(label, bareFrom.getValue(label), x, 1f)
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
        prefs.setShortcuts(KeyShortcuts.Empty.with("zy9/1", KeyShortcut.Text("你好")))
        h = harness(ImKeyboard.ZHUYIN_NINE)
        assertTrue(h.texts().toString(), "你好" in h.texts())
        assertEquals(listOf("ㄅㄉㄚ", "你好"), h.key("ㄅㄉㄚ").drawnTexts)
        h.captureBoard("ZhuyinShortcutsUatTest/nine_text")
        // the shortcut's way: down, the long press's first up
        val group = h.key("ㄅㄉㄚ")
        h.swipe(group, 0f, group.height * 1.3f)
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
        // the page's own actions, and the hint of a key's swipes and its long press by name
        assertTrue(texts.toString(), st.s(R.string.swipe_presets) in texts && st.s(R.string.swipe_flip_all) in texts)
        assertTrue(texts.toString(), st.s(R.string.swipe_slots_hint_page) in texts)
        st.capture("ZhuyinShortcutsUatTest/page_zy9")
    }

    // the 注音九键 tab's key labelled [label], opened (SettingsHarness on the page already)
    private fun SettingsHarness.zy9Key(label: String): View {
        KeyboardHarness.all(activity.window.decorView).filterIsInstance<Chip>().first { it.text.toString() == s(R.string.shortcut_page_zy9) }.performClick()
        idle()
        return KeyboardHarness.all(activity.window.decorView).first { it.contentDescription?.toString()?.substringBefore(',') == label && it.hasOnClickListeners() }
    }

    // review 5: 注音九键's tone keys are on its tab; one edited there is that key's long press on the keyboard
    @Test
    fun aZhuyinToneEditedOnThePageIsTheKeyboardsLongPress() {
        val st = SettingsHarness().also { settings = it }
        prefs.set(AppPrefs.getInstance().internal.keyLists, AppPrefs.getInstance().internal.keyLists.getValue())
        st.open(SettingsRoute.PopupOverrides)
        st.zy9Key("ˉ").performClick()
        st.idle()
        val d = ShadowDialog.getLatestDialog() as AlertDialog
        KeyboardHarness.all(d.window!!.decorView).filterIsInstance<EditText>().first().setText("※")
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        st.idle()
        val key = KeyCaps.resolver(st.activity, KeyCaps.defaultBoard("zy9/tone-1"))!!.resolve(KeyId("zy9/tone-1"), KeyState())
        assertEquals("※", (key.popup as KeyPopup.Items).labels.last())
    }

    // the ， in its place is the function key's: greyed, said so, a tap saying where it is changed and opening no editor
    @Test
    fun zhuyinNinesCommaSaysItIsChangedOnTheFunctionKeysTab() {
        val st = SettingsHarness().also { settings = it }
        st.open(SettingsRoute.PopupOverrides)
        val comma = st.zy9Key("，")
        assertEquals(st.s(R.string.shortcut_key_elsewhere, "，"), ViewCompat.getStateDescription(comma).toString())
        assertTrue(st.texts().toString(), st.s(R.string.long_press_legend_elsewhere, "，") in st.texts())
        val dialogs = ShadowDialog.getShownDialogs().size
        comma.performClick()
        st.idle()
        assertEquals(dialogs, ShadowDialog.getShownDialogs().size)
        assertTrue(st.texts().toString(), st.s(R.string.shortcut_key_elsewhere, "，") in st.texts())
    }
}
