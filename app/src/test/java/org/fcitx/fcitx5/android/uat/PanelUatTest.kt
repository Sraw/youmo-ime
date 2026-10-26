/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.text.InputType
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.data.RecentlyUsed
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.picker.EmojiSearchUi
import org.fcitx.fcitx5.android.input.popup.EmojiModifier
import org.fcitx.fcitx5.android.input.picker.PickerData
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.picker.PickerGridView
import org.fcitx.fcitx5.android.input.picker.PickerLayout
import org.fcitx.fcitx5.android.input.picker.PickerSideUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** UAT cases of the symbol and emoji panels (uat/04). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class PanelUatTest {

    private val h = KeyboardHarness()

    @After
    fun close() {
        h.close()
        AppPrefs.getInstance().internal.symbolPanelLocked.setValue(false)
        // kept in the preferences, which outlive the test: the next panel opens without them
        FcitxApplication.getInstance().directBootAwareContext
            .getSharedPreferences(RecentlyUsed.PREFERENCE_NAME, android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun categories(): List<String> = KeyboardHarness.all(KeyboardHarness.find<PickerSideUi>(h.inputView)!!)
        .filterIsInstance<TextView>().filter { h.shown(it) }.map { it.text.toString() }.toList()

    // the grid binds only the rows on screen: scrolled down a row at a time till the key is among them
    private fun scrolledTo(label: String): TextKeyView {
        val grid = KeyboardHarness.find<PickerGridView>(h.inputView)!!
        repeat(30) {
            KeyboardHarness.all(grid).filterIsInstance<TextKeyView>().firstOrNull { it.mainText.text.toString() == label }?.let { return it }
            grid.scrollBy(0, grid.height / 3)
            h.relayout()
        }
        error("no $label in the grid")
    }

    @Test
    @Uat("PAN-04")
    fun symbolPanelTypesAndCloses() {
        h.start()
        h.tap(h.key("符"))
        val all = listOf("Common", "Chinese", "ASCII", "Brackets", "Arrows", "Math", "Numbering", "Shapes", "Full width")
        assertEquals(all, categories())
        h.capture("PanelUatTest/symbols")
        h.tap(KeyboardHarness.all(h.inputView).filterIsInstance<TextView>().first { h.shown(it) && it.text == "Chinese" })
        h.tap(scrolledTo("《"))
        assertEquals("《", h.field.text.toString())
        // back on the letters
        assertTrue(h.texts().toString(), "q" in h.texts())
        // used once, 最近 heads the list
        h.tap(h.key("符"))
        assertEquals(listOf("Recent") + all, categories())
    }

    @Test
    @Uat("PAN-09")
    fun emojiPanelTabsAndTyping() {
        h.start()
        h.tap(h.described(h.service.getString(R.string.a11y_key_emoji)))
        val tabs = KeyboardHarness.find<PickerLayout>(h.inputView)!!.tabsUi.root
        // 最近使用 first, then Unicode's groups in order
        val groups = PickerData.Emoji.map { h.service.getString(it.first.name) }
        assertEquals(9, groups.size)
        assertEquals(1 + groups.size, tabs.childCount)
        h.capture("PanelUatTest/emoji")
        // 动物与自然: the recent tab, smileys, people, nature
        h.tap(tabs.getChildAt(3))
        h.tap(scrolledTo("🐱"))
        assertEquals("🐱", h.field.text.toString())
        h.capture("PanelUatTest/emoji_nature")
    }

    private fun textView(text: String) =
        KeyboardHarness.all(h.inputView).filterIsInstance<TextView>().first { h.shown(it) && it.text.toString() == text }

    private fun letters() = "q" in h.texts() && "m" in h.texts()

    @Test
    @Uat("PAN-02")
    fun numberAndPhoneFieldsOpenTheNumberPad() {
        for (type in listOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE)) {
            h.start(type)
            val keys = h.texts()
            assertTrue("$type: $keys", "5" in keys && "ABC" in keys && "q" !in keys)
        }
    }

    @Test
    @Uat("PAN-03")
    fun numberPadSymbolsKeyOpensThePanel() {
        h.start()
        h.tap(h.key("123"))
        h.tap(h.key("!?#"))
        assertTrue(categories().toString(), "Common" in categories())
    }

    @Test
    @Uat("PAN-05")
    fun lockedPanelStaysForSeveral() {
        h.start()
        h.tap(h.key("符"))
        h.tap(h.described(h.service.getString(R.string.picker_lock)))
        h.tap(textView("Arrows"))
        for (s in listOf("→", "←", "↑")) h.tap(scrolledTo(s))
        assertEquals("→←↑", h.field.text.toString())
        // still the panel
        assertTrue(categories().toString(), "Arrows" in categories())
    }

    @Test
    @Uat("PAN-07")
    fun recentHasTheLastSymbolFirst() {
        h.start()
        h.tap(h.key("符"))
        h.tap(textView("Shapes"))
        h.tap(scrolledTo("★"))
        h.tap(h.key("符"))
        h.tap(textView("Recent"))
        val grid = KeyboardHarness.find<PickerGridView>(h.inputView)!!
        val first = KeyboardHarness.all(grid).filterIsInstance<TextKeyView>().first { h.shown(it) && it.mainText.text.isNotEmpty() }
        assertEquals("★", first.mainText.text.toString())
    }

    @Test
    @Uat("PAN-08")
    fun panelsNumbersKeyAndBack() {
        h.start()
        h.tap(h.key("符"))
        h.tap(h.described(h.service.getString(R.string.a11y_key_number_pad)))
        assertTrue(h.texts().toString(), "!?#" in h.texts())
        h.tap(h.key("ABC"))
        h.tap(h.key("符"))
        h.tap(h.key(h.service.getString(R.string.picker_back)))
        assertTrue(h.texts().toString(), letters())
    }

    @Test
    @Uat("PAN-10")
    fun theDefaultSkinTone() {
        val tone = AppPrefs.getInstance().symbols.defaultEmojiSkinTone
        tone.setValue(EmojiModifier.SkinTone.Type_3)
        try {
            h.start()
            h.tap(h.described(h.service.getString(R.string.a11y_key_emoji)))
            val tabs = KeyboardHarness.find<PickerLayout>(h.inputView)!!.tabsUi.root
            h.tap(tabs.getChildAt(2))
            h.tap(scrolledTo("👍🏼"))
            assertEquals("👍🏼", h.field.text.toString())
        } finally {
            tone.setValue(tone.defaultValue)
        }
    }

    @Test
    @Uat("PAN-11")
    fun emojiPanelBackToLetters() {
        h.start()
        h.tap(h.described(h.service.getString(R.string.a11y_key_emoji)))
        h.tap(h.key("ABC"))
        assertTrue(h.texts().toString(), letters())
    }

    @Test
    @Uat("PAN-12")
    fun emojiSearchByPinyin() {
        h.start()
        h.tap(h.described(h.service.getString(R.string.a11y_key_emoji)))
        h.tap(h.described(h.service.getString(R.string.emoji_search)))
        for (c in "xiao") h.tap(h.key(c.toString()))
        val row = { KeyboardHarness.find<EmojiSearchUi>(h.inputView)!!.let { ui -> h.texts(ui) } }
        assertTrue(row().toString(), "😄" in row())
        h.capture("PanelUatTest/emoji_search_xiao")
        val search = KeyboardHarness.find<EmojiSearchUi>(h.inputView)!!
        val backspace = KeyboardHarness.all(search).first { it.contentDescription == "Backspace" }
        repeat(4) { h.tap(backspace) }
        for (c in "dz") h.tap(h.key(c.toString()))
        assertTrue(row().toString(), "👍" in row())
        h.tap(KeyboardHarness.all(search).filterIsInstance<TextView>().first { it.text.toString() == "👍" && it.hasOnClickListeners() })
        assertEquals("👍", h.field.text.toString())
        h.tap(h.key(h.service.getString(R.string.picker_back)))
        // the list again, 👍 first among the recent
        val tabs = KeyboardHarness.find<PickerLayout>(h.inputView)!!.tabsUi.root
        h.tap(tabs.getChildAt(0))
        val grid = KeyboardHarness.find<PickerGridView>(h.inputView)!!
        val first = KeyboardHarness.all(grid).filterIsInstance<TextKeyView>().first { h.shown(it) && it.mainText.text.isNotEmpty() }
        assertEquals("👍", first.mainText.text.toString())
    }

    @Test
    @Uat("PAN-14")
    fun numberPadSpaceSlidesTheCursor() {
        h.start()
        h.field.setText("一段长长的文字在这里")
        h.field.setSelection(h.field.length())
        h.idle()
        h.tap(h.key("123"))
        val space = h.described("Space")
        h.api.calls.clear()
        h.swipe(space, -space.width * 0.8f, 0f)
        // ← keys for the cursor (the engine passes them on), no space typed
        val keys = h.api.calls.filter { it.startsWith("sendKey") && "up=false" in it }
        assertTrue(keys.toString(), keys.isNotEmpty() && keys.all { "sym=${FcitxKeyMapping.FcitxKey_Left}," in it })
        assertEquals("一段长长的文字在这里", h.field.text.toString())
        h.tap(h.key("1"))
        assertTrue(h.api.calls.last { it.startsWith("sendKey") && "up=false" in it }.contains("sym=${FcitxKeyMapping.FcitxKey_KP_1},"))
    }

    @Test
    @Uat("PAN-15")
    fun ownFieldsKeepNoRecentEmoji() {
        h.start(packageName = h.service.packageName)
        val open = { h.tap(h.described(h.service.getString(R.string.a11y_key_emoji))) }
        val nature = { h.tap(KeyboardHarness.find<PickerLayout>(h.inputView)!!.tabsUi.root.getChildAt(3)) }
        fun recent(): List<String> {
            h.tap(KeyboardHarness.find<PickerLayout>(h.inputView)!!.tabsUi.root.getChildAt(0))
            val grid = KeyboardHarness.find<PickerGridView>(h.inputView)!!
            return KeyboardHarness.all(grid).filterIsInstance<TextKeyView>().filter { h.shown(it) }.map { it.mainText.text.toString() }.toList()
        }
        open(); nature(); h.tap(scrolledTo("🐱"))
        h.tap(h.key("ABC"))
        // another app's field
        h.start()
        open()
        assertFalse(recent().toString(), "🐱" in recent())
        nature(); h.tap(scrolledTo("🐱"))
        h.tap(h.key("ABC"))
        open()
        assertEquals("🐱", recent().first { it.isNotEmpty() })
    }
}
