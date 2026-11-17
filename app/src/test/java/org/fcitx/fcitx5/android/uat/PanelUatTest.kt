/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.text.InputType
import android.view.View
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.data.RecentlyUsed
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.input.SceneShownKey
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKeyCell
import org.fcitx.fcitx5.android.input.picker.EmojiSearchUi
import org.fcitx.fcitx5.android.input.popup.EmojiModifier
import org.fcitx.fcitx5.android.input.picker.PickerData
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupSceneView
import org.fcitx.fcitx5.android.input.picker.PickerGridView
import org.fcitx.fcitx5.android.input.picker.PickerLayout
import org.fcitx.fcitx5.android.input.picker.PickerSideUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
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

    @get:Rule
    val prefs = PrefsRule()

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
    private fun scrolledTo(label: String): View {
        val grid = KeyboardHarness.find<PickerGridView>(h.inputView)!!
        repeat(30) {
            KeyboardHarness.cells(grid).firstOrNull { KeyboardHarness.cellText(it) == label }?.let { return it }
            grid.scrollBy(0, grid.height / 3)
            h.relayout()
        }
        error("no $label in the grid")
    }

    // a symbol's cell held: its bubble the engine's (S8a: the grids' popups drawn as every key's), the symbol typed
    @Test
    @Uat("PAN-21")
    fun aSymbolHeldShowsItsBubble() {
        h.start()
        h.tap(h.key("符"))
        val first = KeyboardHarness.all(KeyboardHarness.find<PickerGridView>(h.inputView)!!)
            .filterIsInstance<SceneKeyCell>().first { h.shown(it) }
        val symbol = first.keyNode?.label.orEmpty()
        val finger = h.finger(first, h.at(first))
        h.after(AppPrefs.getInstance().keyboard.longPressDelay.getValue() + 100L)
        assertTrue(KeyboardHarness.all(h.inputView).any { it is PopupSceneView && h.shown(it) })
        finger.up(h.at(first))
        h.idle()
        assertEquals(symbol, h.field.text.toString())
    }

    @Test
    @Uat("PAN-04")
    fun symbolPanelTypesAndCloses() {
        h.start()
        h.tap(h.key("符"))
        val all = listOf("Common", "Chinese", "ASCII", "Brackets", "Arrows", "Math", "Numbering", "Shapes", "Full width")
        assertEquals(all, categories())
        h.captureBoard("PanelUatTest/symbols")
        h.tap(KeyboardHarness.all(h.inputView).filterIsInstance<TextView>().first { h.shown(it) && it.text == "Chinese" })
        h.tap(scrolledTo("《"))
        // in a pair, as typed (PAN-30)
        assertEquals("《》", h.field.text.toString())
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

    // the field with | at the cursor
    private fun shown() = h.field.text.toString().let { t -> t.substring(0, h.field.selectionStart) + "|" + t.substring(h.field.selectionStart) }

    // the symbol panel opened on 中文, [symbol] tapped: the panel closes after it
    private fun picked(symbol: String) {
        h.tap(h.key("符"))
        h.tap(textView("Chinese"))
        h.tap(scrolledTo(symbol))
    }

    // fcitx hands Backspace back with nothing typed (the fake simulates no input): the service deletes
    private fun backspace() {
        h.tap(h.keyWithId(R.id.button_backspace)!!)
        h.emit(HostEvent.Key(FcitxKeyMapping.FcitxKey_BackSpace, KeyStates.Virtual.states.toInt(), 0, false, 0))
    }

    // FB9: a bracket from the symbol panel goes in paired, as typed; Backspace in the empty pair takes both
    @Test
    @Uat("PAN-30")
    fun aBracketFromThePanelGoesInPairedAndBackspaceTakesBoth() {
        h.start()
        picked("《")
        assertEquals("《|》", shown())
        backspace()
        assertEquals("|", shown())
    }

    // the closing one from the panel steps over the one put in, as typed
    @Test
    @Uat("PAN-31")
    fun theClosingOneFromThePanelStepsOverTheOnePutIn() {
        h.start()
        picked("「")
        assertEquals("「|」", shown())
        picked("」")
        assertEquals("「」|", shown())
    }

    // the number keyboard's ( and ) (a dialler's pad) alone, as typed: a dialler formats the number
    @Test
    @Uat("PAN-32")
    fun inAPhoneNumbersFieldThePadsBracketsAreAlone() {
        // a dialler formats the number as it is typed: a bracket put in for it would be in its way
        h.start(InputType.TYPE_CLASS_PHONE)
        h.tap(h.key("("))
        assertEquals("(|", shown())
        h.tap(h.key(")"))
        assertEquals("()|", shown())
    }

    // a URL's `)` typed on the keyboard goes in as it is, over the engine: it still steps over the one the panel's
    // `(` put in, not `())`
    @Test
    @Uat("PAN-34")
    fun inAnAddressTheKeysClosingOneStepsOverThePanels() {
        // an address field has no comma key: p's long press is `)` here
        prefs.setList("full/p", listOf(")"))
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        h.tap(h.key("符"))
        h.tap(textView("ASCII"))
        h.tap(scrolledTo("("))
        assertEquals("(|)", shown())
        val p = h.keys().filterIsInstance<SceneShownKey>()
            .first { it.node.copy == 0 && it.node.shortcut?.let { s -> KeyId.of(s).name } == "full/p" }
        h.longPress(p, pick = ")")
        h.idle()
        assertEquals("()|", shown())
    }

    // where brackets are not paired (括号成对 off, a password) the panel's goes in alone
    @Test
    @Uat("PAN-33")
    fun offOrInAPasswordThePanelsBracketIsAlone() {
        val keyboard = AppPrefs.getInstance().keyboard
        prefs.set(keyboard.autoPairs, false)
        h.start()
        picked("《")
        assertEquals("《|", shown())
        // on again: the password alone keeps it single
        prefs.set(keyboard.autoPairs, true)
        h.start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        picked("《")
        assertEquals("《|", shown())
    }

    private fun textView(text: String) =
        KeyboardHarness.all(h.inputView).filterIsInstance<TextView>().first { h.shown(it) && it.text.toString() == text }

    private fun letters() = "q" in h.texts() && "m" in h.texts()

    // the way back of the number pads and the panels: 返回 (Back here), no longer ABC (0.6.1, 061-layout-survey Q2)
    private fun back() = h.service.getString(R.string.picker_back)

    // KB-92: every way back says 返回, the pad a number or phone field opens with and the emoticons' too, as the symbol panel's
    @Test
    @Uat("KB-92")
    fun everyWayBackSaysBack() = assertRows(
        listOf<Pair<String, () -> Any>>(
            "a number field's pad" to { h.start(InputType.TYPE_CLASS_NUMBER) },
            "a phone field's pad" to { h.start(InputType.TYPE_CLASS_PHONE) },
            "the 123 key's pad" to { h.start(); h.tap(h.key("123")) },
            "the emoji panel" to { h.start(); h.tap(h.described(h.service.getString(R.string.a11y_key_emoji))) },
            "the emoticon panel" to { h.start(); h.tap(h.described(h.service.getString(R.string.a11y_key_emoji))); h.tap(h.key(":-)")) },
            "the symbol panel, which said it already" to { h.start(); h.tap(h.key("符")) },
        ),
        { (where, _) -> where },
    ) { (_, open) ->
        open()
        assertEquals(back(), h.keyNamed("back").label)
        assertFalse(h.texts().toString(), "ABC" in h.texts())
    }

    @Test
    @Uat("PAN-02")
    fun numberAndPhoneFieldsOpenTheNumberPad() {
        for (type in listOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE)) {
            h.start(type)
            val keys = h.texts()
            assertTrue("$type: $keys", "5" in keys && back() in keys && "q" !in keys)
        }
    }

    @Test
    @Uat("PAN-03")
    fun numberPadSymbolsKeyOpensThePanel() {
        h.start()
        h.tap(h.key("123"))
        h.tap(h.key("符"))
        assertTrue(categories().toString(), "Common" in categories())
    }

    // the category shown in bold on the side: the one the panel shows
    private fun shownCategory(of: KeyboardHarness = h): String = KeyboardHarness.all(KeyboardHarness.find<PickerSideUi>(of.inputView)!!)
        .filterIsInstance<TextView>().single { of.shown(it) && it.typeface?.isBold == true }.text.toString()

    // user 2026-11-13: till a category is picked the panel opens on 最近 once a symbol was used, else on 常用;
    // once one is, on the one shown last
    @Test
    @Uat("PAN-29")
    fun thePanelOpensOnTheRecentlyUsedTillACategoryIsPicked() {
        h.start()
        h.tap(h.key("符"))
        assertEquals("Common", shownCategory())
        h.tap(scrolledTo("？"))
        h.tap(h.key("符"))
        assertEquals("nothing picked, a symbol used", "Recent", shownCategory())
        h.tap(textView("Arrows"))
        h.tap(scrolledTo("→"))
        h.tap(h.key("符"))
        assertEquals("picked, then reopened", "Arrows", shownCategory())
    }

    // user 2026-11-14: the pick kept across a restart (a keyboard made anew reads it), a reopen being none; one
    // kept that is no longer a category (renamed, gone) as none picked
    @Test
    @Uat("PAN-29")
    fun theCategoryPickedIsKeptForTheNextStart() {
        val kept = AppPrefs.getInstance().internal.symbolPanelCategory
        h.start()
        h.tap(h.key("符"))
        h.tap(scrolledTo("？"))
        h.tap(h.key("符"))
        assertEquals("opened on the recently used, not picked: nothing kept", "", kept.getValue())
        h.tap(textView("Arrows"))
        h.tap(scrolledTo("→"))
        assertEquals("arrows", kept.getValue())
        h.close()
        KeyboardHarness().use { again ->
            again.start()
            again.tap(again.key("符"))
            assertEquals("the next start: the one picked", "Arrows", shownCategory(again))
        }
        prefs.set(kept, "gone")
        KeyboardHarness().use { again ->
            again.start()
            again.tap(again.key("符"))
            assertEquals("kept, but no such category: as none picked, a symbol used", "Recent", shownCategory(again))
        }
    }

    @Test
    @Uat("PAN-05")
    fun lockedPanelStaysForSeveral() {
        h.start()
        h.tap(h.key("符"))
        h.tap(h.describedKey(h.service.getString(R.string.picker_lock)))
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
        val first = KeyboardHarness.cells(grid).first { h.shown(it) && KeyboardHarness.cellText(it).isNotEmpty() }
        assertEquals("★", KeyboardHarness.cellText(first))
    }

    @Test
    @Uat("PAN-08")
    fun panelsNumbersKeyAndBack() {
        h.start()
        h.tap(h.key("符"))
        h.tap(h.key("123"))
        assertTrue(h.texts().toString(), "=" in h.texts())
        h.tap(h.key(back()))
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
        h.tap(h.key(back()))
        assertTrue(h.texts().toString(), letters())
    }

    @Test
    @Uat("PAN-12")
    fun emojiSearchByPinyin() {
        h.start()
        h.tap(h.described(h.service.getString(R.string.a11y_key_emoji)))
        h.tap(h.describedKey(h.service.getString(R.string.emoji_search)))
        for (c in "xiao") h.tap(h.key(c.toString()))
        val row = { KeyboardHarness.find<EmojiSearchUi>(h.inputView)!!.let { ui -> h.texts(ui) } }
        assertTrue(row().toString(), "😄" in row())
        h.captureBoard("PanelUatTest/emoji_search_xiao")
        val search = KeyboardHarness.find<EmojiSearchUi>(h.inputView)!!
        val backspace = h.keys(search).first { it.description == "Backspace" }
        repeat(4) { h.tap(backspace) }
        for (c in "dz") h.tap(h.key(c.toString()))
        assertTrue(row().toString(), "👍" in row())
        h.tap(KeyboardHarness.all(search).filterIsInstance<TextView>().first { it.text.toString() == "👍" && it.hasOnClickListeners() })
        assertEquals("👍", h.field.text.toString())
        // the search's own 返回: the panel's, under the search, says 返回 too now (0.6.1)
        h.tap(h.keys(search).first { it.label == back() })
        // the list again, 👍 first among the recent
        val tabs = KeyboardHarness.find<PickerLayout>(h.inputView)!!.tabsUi.root
        h.tap(tabs.getChildAt(0))
        val grid = KeyboardHarness.find<PickerGridView>(h.inputView)!!
        val first = KeyboardHarness.cells(grid).first { h.shown(it) && KeyboardHarness.cellText(it).isNotEmpty() }
        assertEquals("👍", KeyboardHarness.cellText(first))
    }

    @Test
    @Uat("PAN-14")
    fun numberPadSpaceSlidesTheCursor() {
        h.start()
        h.field.setText("一段长长的文字在这里")
        h.field.setSelection(h.field.length())
        h.idle()
        h.tap(h.key("123"))
        val space = h.describedKey("Space")
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
            return KeyboardHarness.cells(grid).filter { h.shown(it) }.map(KeyboardHarness::cellText).toList()
        }
        open(); nature(); h.tap(scrolledTo("🐱"))
        h.tap(h.key(back()))
        // another app's field
        h.start()
        open()
        assertFalse(recent().toString(), "🐱" in recent())
        nature(); h.tap(scrolledTo("🐱"))
        h.tap(h.key(back()))
        open()
        assertEquals("🐱", recent().first { it.isNotEmpty() })
    }
}
