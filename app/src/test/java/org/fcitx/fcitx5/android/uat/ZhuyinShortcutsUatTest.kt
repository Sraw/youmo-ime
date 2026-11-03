/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.google.android.material.chip.Chip
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
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

    private fun KeyboardHarness.faced(face: String): TextKeyView =
        KeyboardHarness.all(keyboard).filterIsInstance<TextKeyView>().first { it.mainText.text.toString() == face }

    // the key's marks: icons drawn in its corners
    private fun icons(v: View): Int = when (v) {
        is ImageView -> if (v.visibility == View.VISIBLE && v.drawable != null) 1 else 0
        is ViewGroup -> (0 until v.childCount).sumOf { icons(v.getChildAt(it)) }
        else -> 0
    }

    @Test
    @Uat("PY-107")
    fun dachensKeysTakeTheFullKeyboardsShortcutsByPlace() {
        val h = harness(ImKeyboard.ZHUYIN_FULL)
        h.field.setText("one two")
        h.idle()
        // ㄇ on a: select all, its icon drawn; ㄋ on s: none
        val m = h.faced("ㄇ")
        assertEquals(1, icons(m))
        assertEquals(0, icons(h.faced("ㄋ")))
        for (face in listOf("ㄏ", "ㄌ", "ㄒ", "ㄈ", "ㄗ")) assertEquals(face, 1, icons(h.faced(face)))
        h.capture("ZhuyinShortcutsUatTest/dachen")
        h.swipe(m, 0f, -m.height * 1.3f)
        h.idle()
        assertEquals(0 to 7, h.field.selectionStart to h.field.selectionEnd)
    }

    @Test
    @Uat("T9-61")
    fun zhuyinNinesGroupsHaveNoneUntilOneIsSet() {
        var h = harness(ImKeyboard.ZHUYIN_NINE)
        assertEquals("no preset", 0, icons(h.faced("ㄅㄉㄚ")))
        h.close()
        prefs.set(AppPrefs.getInstance().internal.keyShortcuts, KeyShortcuts.Empty.with("zy9/1", KeyShortcut.Text("你好")).serialize())
        h = harness(ImKeyboard.ZHUYIN_NINE)
        assertTrue(h.texts().toString(), "你好" in h.texts())
        h.capture("ZhuyinShortcutsUatTest/nine_text")
        val group = h.faced("ㄅㄉㄚ")
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
        assertTrue(chips.toString(), nine >= 0 && chips[nine + 1] == st.s(R.string.shortcut_page_zyfull))
        KeyboardHarness.all(st.activity.window.decorView).filterIsInstance<Chip>().first { it.text.toString() == chips[nine] }.performClick()
        st.idle()
        val texts = st.texts()
        for (g in listOf("ㄅㄉㄚ", "ㄓㄗㄢㄦ", "ㄖㄥㄩ")) assertTrue("$g in $texts", g in texts)
        st.capture("ZhuyinShortcutsUatTest/page_zy9")
    }
}
