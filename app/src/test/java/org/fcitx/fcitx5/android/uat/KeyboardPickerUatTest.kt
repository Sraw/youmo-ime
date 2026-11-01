/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.view.View
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.EnglishKeyboard
import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowPopupMenu

/**
 * ⋯'s 键盘 (I1 Q1, uat/05 TB-47): the keyboards the input method on offers, the one in use ticked;
 * one picked is its page's setting, the keyboard made anew on it; none for an input method with no
 * choice. The entry's place among ⋯'s own is the toolbar's (ToolbarUatTest).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi", shadows = [HideRecordingShadow::class])
class KeyboardPickerUatTest {

    private var harness: KeyboardHarness? = null
    private val h: KeyboardHarness get() = harness!!

    @get:Rule
    val prefs = PrefsRule()

    // the process's scheme as the test found it: a later test reads it
    private val schemeWas = EngineState.shuangpinScheme

    @After
    fun close() {
        harness?.close()
        EngineState.shuangpinScheme = schemeWas
    }

    private fun s(id: Int) = h.service.getString(id)

    private fun entry(name: String) = InputMethodEntry(name, name, "", "", name, "zh_CN", "androidengine", false)

    private fun start(): KeyboardHarness = KeyboardHarness().also {
        harness = it
        it.api.availableIme = arrayOf(KeyboardHarness.PINYIN, KeyboardHarness.ENGLISH, entry(InputMethodCatalog.SHUANGPIN), entry(InputMethodCatalog.WUBI))
        it.api.enabledIme = it.api.availableIme
        it.start()
    }

    private fun switchTo(im: InputMethodEntry) {
        h.api.inputMethodEntryCached = im
        h.emit(HostEvent.InputMethodChanged(im))
    }

    // ⋯ opened from the toolbar
    private fun statusArea() {
        if (KeyboardHarness.all(h.inputView).none { h.shown(it) && it.contentDescription == s(R.string.hide_toolbar) }) {
            h.tap(h.described(s(R.string.expand_toolbar)))
            h.after(500)
            h.relayout()
        }
        h.tap(h.described(s(R.string.status_area)))
    }

    private fun button(text: String): View? = KeyboardHarness.all(h.inputView)
        .firstOrNull { h.shown(it) && it is TextView && it.text.toString() == text }
        ?.let { t -> generateSequence(t) { it.parent as? View }.first { it.hasOnClickListeners() } }

    // ⋯ → 键盘: the list's items, the ticked one, and a pick of [pick]
    private fun picked(pick: Int? = null): Pair<List<String>, String> {
        statusArea()
        h.tap(button(s(R.string.status_keyboard)) ?: error("no 键盘 in ${h.texts(h.inputView)}"))
        val menu = ShadowPopupMenu.getLatestPopupMenu().menu
        val items = (0 until menu.size()).map { menu.getItem(it) }
        val shown = items.map { it.title.toString() } to items.single { it.isChecked }.title.toString()
        if (pick != null) {
            val item = items.first { it.title.toString() == s(pick) }
            shadowOf(ShadowPopupMenu.getLatestPopupMenu()).onMenuItemClickListener.onMenuItemClick(item)
            h.idle()
            h.relayout()
        }
        return shown
    }

    @Test
    @Uat("TB-47")
    fun eachInputMethodsKeyboardsPickedFromTheStatusArea() {
        val keyboard = AppPrefs.getInstance().keyboard
        prefs.set(keyboard.pinyinKeyboard, PinyinKeyboard.Full)
        prefs.set(keyboard.shuangpinKeyboard, ShuangpinKeyboard.Full)
        prefs.set(keyboard.englishKeyboard, EnglishKeyboard.Full)
        start()
        // pinyin: the four, 全键盘 ticked; the nine keys picked, the keyboard is them
        val four = listOf(R.string.pinyin_keyboard_full, R.string.keyboard_18, R.string.pinyin_keyboard_14, R.string.keyboard_nine).map(::s)
        assertEquals(four to s(R.string.pinyin_keyboard_full), picked(R.string.keyboard_nine))
        assertEquals(PinyinKeyboard.NineKeys, keyboard.pinyinKeyboard.getValue())
        assertTrue(h.texts().toString(), "重输" in h.texts())
        assertEquals(four to s(R.string.keyboard_nine), picked())
        h.start()
        // English: its own two, the nine keys picked moves no other's
        switchTo(KeyboardHarness.ENGLISH)
        val two = listOf(R.string.pinyin_keyboard_full, R.string.keyboard_nine).map(::s)
        assertEquals(two to s(R.string.pinyin_keyboard_full), picked(R.string.keyboard_nine))
        assertEquals(EnglishKeyboard.NineKeys, keyboard.englishKeyboard.getValue())
        assertEquals(PinyinKeyboard.NineKeys, keyboard.pinyinKeyboard.getValue())
        h.start()
        // 双拼 under 微软: no 14 keys (its ; is ing)
        EngineState.shuangpinScheme = ShuangpinScheme.MICROSOFT
        switchTo(entry(InputMethodCatalog.SHUANGPIN))
        val three = listOf(R.string.pinyin_keyboard_full, R.string.keyboard_18, R.string.keyboard_nine).map(::s)
        assertEquals(three to s(R.string.pinyin_keyboard_full), picked(R.string.keyboard_18))
        assertEquals(ShuangpinKeyboard.Keys18, keyboard.shuangpinKeyboard.getValue())
        h.start()
        // 五笔: no choice, no entry
        switchTo(entry(InputMethodCatalog.WUBI))
        statusArea()
        assertFalse(h.texts(h.inputView).toString(), s(R.string.status_keyboard) in h.texts(h.inputView))
    }

    @Test
    @Uat("TB-43")
    fun theKeyboardButtonOnTheBarIsThePanelsPicker() {
        val keyboard = AppPrefs.getInstance().keyboard
        prefs.set(keyboard.pinyinKeyboard, PinyinKeyboard.Full)
        prefs.set(AppPrefs.getInstance().internal.toolbarButtons, "undo,|,keyboard,hide")
        start()
        // at the bar's right: the same list as ⋯'s, the one in use ticked; a pick is the keyboard
        h.tap(h.described(s(R.string.status_keyboard)))
        val menu = ShadowPopupMenu.getLatestPopupMenu().menu
        val items = (0 until menu.size()).map { menu.getItem(it) }
        val four = listOf(R.string.pinyin_keyboard_full, R.string.keyboard_18, R.string.pinyin_keyboard_14, R.string.keyboard_nine).map(::s)
        assertEquals(four, items.map { it.title.toString() })
        assertEquals(s(R.string.pinyin_keyboard_full), items.single { it.isChecked }.title.toString())
        shadowOf(ShadowPopupMenu.getLatestPopupMenu()).onMenuItemClickListener.onMenuItemClick(items.first { it.title.toString() == s(R.string.keyboard_nine) })
        h.idle()
        assertEquals(PinyinKeyboard.NineKeys, keyboard.pinyinKeyboard.getValue())
        // 五笔 has no choice: no button
        h.start()
        switchTo(entry(InputMethodCatalog.WUBI))
        h.idle()
        h.relayout()
        assertFalse(KeyboardHarness.all(h.inputView).any { h.shown(it) && it.contentDescription == s(R.string.status_keyboard) })
    }
}
