/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.layout.CustomKeyboards
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightCap
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.MarkFate
import org.fcitx.fcitx5.android.scene.engine.FixedMeasurer
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A function key's way as the settings page tells it: the scenes name it `fn/backspace`, the function tab by its
 * bare `backspace`; both read as the one name, a real keyboard's scene made. A table's roots fill its letters alone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class SwipeFatesTest {

    @get:Rule
    val prefs = PrefsRule()

    @get:Rule
    val folder = TemporaryFolder()

    // both sides measure a character alike (Robolectric measures no text)
    @Before
    fun fixedWidths() {
        SceneBoard.measurerFor = { FixedMeasurer }
    }

    @After
    fun fontsWidths() {
        SceneBoard.measurerFor = null
    }

    // ⌫'s 下滑 a long text, the keyboard low, the way at the bottom: far more than its corner holds
    private fun setBackspace(): SwipeSlots {
        val k = AppPrefs.getInstance().keyboard
        prefs.set(k.swipeDownPosition, KeyMarkPosition.Bottom)
        prefs.set(k.keyboardHeightPercent, 20)
        prefs.set(k.keyboardHeightPercentLandscape, 34)
        val slots = SwipeSlots.Default.with("fn/backspace", false, SwipeSlot.Text("删掉这一整句话删掉这一整句话删掉这一整句话"))
        prefs.set(AppPrefs.getInstance().internal.keySwipes, slots.serialize())
        return slots
    }

    @Test
    fun aFunctionKeysWayIsNotedByItsPagesName() {
        val slots = setBackspace()
        KeyboardHarness(TestThemes.PixelLight).start().use { h ->
            val seen = SwipeFates.Page(h.service, listOf(KeyboardHarness.PINYIN), emptyMap()).jobs(slots).map(SwipeFates::made)
            val fates = seen.map { it.fates!!.getValue("fn/backspace")[false] }
            assertTrue(fates.toString(), fates.any { it != MarkFate.AsSet })
            val note = SwipeFates.note(h.service, seen, "backspace", up = false)
            assertNotNull(note)
            assertEquals(note, SwipeFates.note(h.service, seen, "fn/backspace", up = false))
        }
    }

    // a way set to 空 draws nothing: no fate, no note (never 画不下)
    @Test
    fun aWaySetEmptyIsNotNoted() {
        setBackspace()
        val slots = SwipeSlots.Default.with("full/j", false, SwipeSlot.Empty)
        prefs.set(AppPrefs.getInstance().internal.keySwipes, slots.serialize())
        KeyboardHarness(TestThemes.PixelLight).start().use { h ->
            val seen = SwipeFates.Page(h.service, listOf(KeyboardHarness.PINYIN), emptyMap()).jobs(slots).map(SwipeFates::made)
            assertNull(SwipeFates.note(h.service, seen, "full/j", up = false))
        }
    }

    @Test
    fun aTablesRootsFillItsLettersAloneNotTheFunctionKeys() {
        val slots = setBackspace()
        // the roots fill a full keyboard alone: one not 紧凑 (setBackspace's 20% is)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 30)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 49)
        val wubi = InputMethodEntry(InputMethodCatalog.WUBI, "五笔", "", "", "五", "zh_CN", "androidengine", false)
        KeyboardHarness(TestThemes.PixelLight).start().use { h ->
            val page = SwipeFates.Page(h.service, listOf(wubi), mapOf(InputMethodCatalog.WUBI to KeyLabels.ROOTS.key))
            val seen = page.jobs(slots).map(SwipeFates::made)
            assertTrue(seen.all { it.roots.isNotEmpty() })
            seen.forEach { s -> assertTrue(s.roots.toString(), s.roots.none { KeyId(it).isFunction }) }
            assertNotEquals(h.service.getString(R.string.swipe_fate_roots), SwipeFates.note(h.service, seen, "backspace", up = false))
        }
    }

    // a layout of the user's an input method draws: its keyboard named by the layout's name (061-custom-layout §9, L6)
    @Test
    @Uat("ST-187")
    fun aLayoutOfTheUsersIsNamedByItsName() {
        val slots = setBackspace()
        val dir = java.io.File(folder.root, "layouts").apply { mkdirs() }
        val dirWas = CustomKeyboards.dir
        CustomKeyboards.dir = { dir }
        try {
            val ctx = RuntimeEnvironment.getApplication()
            java.io.File(dir, "my-qwerty.xml").writeText(BuiltInLayouts(ctx).text("qwerty")!!.replaceFirst("id=\"qwerty\" name=\"26 键\"", "id=\"my-qwerty\" name=\"我的 26 键\" based-on=\"qwerty\""))
            CustomKeyboards.forget("my-qwerty")
            val k = AppPrefs.getInstance().keyboard
            prefs.set(k.pinyinKeyboard, k.pinyinKeyboard.getValue())
            prefs.set(k.pinyinKeyboardLayout, "")
            k.setImKeyboard(InputMethodCatalog.PINYIN, ImKeyboard.CUSTOM, "my-qwerty")
            KeyboardHarness(TestThemes.PixelLight).start().use { h ->
                val english = InputMethodEntry(InputMethodCatalog.ENGLISH, "English", "", "", "En", "en", "androidengine", false)
                val seen = SwipeFates.Page(h.service, listOf(KeyboardHarness.PINYIN, english), emptyMap()).jobs(slots).map(SwipeFates::made)
                // pinyin's the layout's name, English's (the app's 26 keys) the app's: two keyboards, no input method's name after either
                assertEquals(setOf("我的 26 键", h.service.getString(R.string.shortcut_page_full)), seen.map { it.label }.toSet())
            }
        } finally {
            CustomKeyboards.dir = dirWas
            CustomKeyboards.forget("my-qwerty")
        }
    }

    @Test
    @Config(qualifiers = "w780dp-h380dp-land-440dpi")
    fun turnedZhuyinsNotesAreOfItsFiveRows() {
        // a small phone turned at 很高: the letters' four rows give way to the app's 120 dp, 注音's five keep 36 dp each,
        // as the keyboard has them (ImeRoomViewTest): the page's notes are of the keys as tall as there
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 60)
        prefs.set(AppPrefs.getInstance().internal.zhuyinTraditionalTold, true)
        val turned = KeyboardArea.screens(RuntimeEnvironment.getApplication()).second
        val four = SwipeFates.size(turned, "zhuyin", ImKeyboards.Layout.TEXT).height
        val five = SwipeFates.size(turned, "zhuyin", ImKeyboards.Layout.TEXT, 5.0).height
        assertEquals(turned.dp(5.0 * KeyboardHeightCap.MIN_ROW_DP), five)
        assertTrue("$four < $five", four < five)
        // the rows the page asks the board of (SwipeFates.jobs), the keyboard's own
        KeyboardHarness(keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.ZHUYIN_FULL)).start().use { h ->
            val board = KeyboardHarness.all(h.inputView).filterIsInstance<BaseKeyboard>().first { it.isShown }
            assertEquals(5.0, KeyboardWindow.rowUnits(board, ThemeManager.shownLook(ThemeManager.activeTheme)), 0.0)
        }
    }
}
