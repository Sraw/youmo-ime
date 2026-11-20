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
import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmClock
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.KeyClock
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightCap
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneKeyCell
import org.fcitx.fcitx5.android.input.keyboard.forgetTheTestsLayouts
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.MarkFate
import org.fcitx.fcitx5.android.scene.engine.FixedMeasurer
import org.fcitx.fcitx5.android.scene.engine.MarkContent
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
import java.util.Calendar
import java.util.SimpleTimeZone

/** The scenes name a function key `fn/backspace`, the function tab bare `backspace`: both read as one name. */
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

    // a long text on ⌫'s 下滑 with the keyboard low: far more than its corner holds
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

    // 空 draws nothing: no fate, no note
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
        // a table's roots fill only a full keyboard, not 紧凑
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercent, 30)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 49)
        val wubi = InputMethodEntry(InputMethodCatalog.WUBI, "五笔", "", "五", "zh_CN")
        KeyboardHarness(TestThemes.PixelLight).start().use { h ->
            val page = SwipeFates.Page(h.service, listOf(wubi), mapOf(InputMethodCatalog.WUBI to KeyLabels.ROOTS.key))
            val seen = page.jobs(slots).map(SwipeFates::made)
            assertTrue(seen.all { it.roots.isNotEmpty() })
            seen.forEach { s -> assertTrue(s.roots.toString(), s.roots.none { KeyId(it).isFunction }) }
            assertNotEquals(h.service.getString(R.string.swipe_fate_roots), SwipeFates.note(h.service, seen, "backspace", up = false))
        }
    }

    // a user layout's keyboard is named by the layout's name
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
                val english = InputMethodEntry(InputMethodCatalog.ENGLISH, "English", "", "En", "en")
                val seen = SwipeFates.Page(h.service, listOf(KeyboardHarness.PINYIN, english), emptyMap()).jobs(slots).map(SwipeFates::made)
                assertEquals(setOf("我的 26 键", h.service.getString(R.string.shortcut_page_full)), seen.map { it.label }.toSet())
            }
        } finally {
            forgetTheTestsLayouts(dirWas)
            CustomKeyboards.forget("my-qwerty")
        }
    }

    // a cap of a key on no board the app reads fills its variables on the keys' clock (KeyClock), as the keyboard does
    @Test
    fun aCapOnNoBoardFillsItsVariablesInOnTheKeysClock() {
        // half an hour off the device's clock: their minutes never match during the test
        val minute = (Calendar.getInstance().get(Calendar.MINUTE) + HALF_HOUR) % HOUR
        val at = Calendar.getInstance(SimpleTimeZone(UTC8, "t")).apply { clear(); set(2001, Calendar.MARCH, 4, 9, minute) }.timeInMillis
        KeyClock.clock = object : LearnClock {
            override fun now() = at
            override fun offset(at: Long) = UTC8
        }
        try {
            val ctx = RuntimeEnvironment.getApplication()
            val slots = SwipeSlots.Default.with("full/w", false, SwipeSlot.Text("\$minute", variables = true))
            val cell = SceneKeyCell(ctx, ThemeManager.activeTheme)
            with(KeyCaps) { cell.onBoard(ctx, KeyCaps.Shortcut("full/w", slots, layout = "gone"), null) }
            val texts = cell.marks.shortcuts.values.filterIsInstance<MarkContent.Text>().map { it.text }
            assertTrue(texts.toString(), "%02d".format(minute) in texts)
        } finally {
            KeyClock.clock = JvmClock
        }
    }

    // grouped keys based on the full keyboard are sized as the keyboard draws them, not as the board they take styles of
    @Test
    fun aCapOfGroupedKeysBasedOnTheFullKeyboardIsOfItsOwnFamilysSize() {
        prefs.setStyle(KeyboardStyle.SPLIT)
        val dir = java.io.File(folder.root, "layouts").apply { mkdirs() }
        val dirWas = CustomKeyboards.dir
        CustomKeyboards.dir = { dir }
        try {
            val ctx = RuntimeEnvironment.getApplication()
            val k18 = BuiltInLayouts(ctx).text("k18")!!
            java.io.File(dir, "cross.xml").writeText(k18.replaceFirst("id=\"k18\"", "id=\"cross\" based-on=\"qwerty\""))
            CustomKeyboards.forget("cross")
            fun size(layout: String) = KeyCaps.board(ctx, "full/q", layout).let { listOf(it.width, it.split) }
            assertNotEquals(size("qwerty"), size("k18"))
            assertEquals(size("k18"), size("cross"))
        } finally {
            forgetTheTestsLayouts(dirWas)
        }
    }

    @Test
    @Config(qualifiers = "w780dp-h380dp-land-440dpi")
    fun turnedZhuyinsNotesAreOfItsFiveRows() {
        // a small phone turned at 很高: the letters' rows give way to 120 dp, 注音's five keep 36 dp each (ImeRoomViewTest)
        prefs.set(AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape, 60)
        prefs.set(AppPrefs.getInstance().internal.zhuyinTraditionalTold, true)
        val turned = KeyboardArea.screens(RuntimeEnvironment.getApplication()).second
        val four = SwipeFates.size(turned, "zhuyin", ImKeyboards.Layout.TEXT).height
        val five = SwipeFates.size(turned, "zhuyin", ImKeyboards.Layout.TEXT, 5.0).height
        assertEquals(turned.dp(5.0 * KeyboardHeightCap.MIN_ROW_DP), five)
        assertTrue("$four < $five", four < five)
        KeyboardHarness(keyboards = ImKeyboards.Chosen(pinyin = ImKeyboard.ZHUYIN_FULL)).start().use { h ->
            val board = KeyboardHarness.all(h.inputView).filterIsInstance<BaseKeyboard>().first { it.isShown }
            assertEquals(5.0, KeyboardWindow.rowUnits(board, ThemeManager.shownLook(ThemeManager.activeTheme)), 0.0)
        }
    }

    private companion object {
        const val UTC8 = 8 * 3_600_000
        const val HALF_HOUR = 30
        const val HOUR = 60
    }
}
