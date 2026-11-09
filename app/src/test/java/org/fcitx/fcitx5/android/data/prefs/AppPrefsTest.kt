/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows as assertNamedRows
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.keyboard.LangSwitchBehavior
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayers
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayersChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A setting moved out of the settings page kept what an older version or a backup set, with
 * nothing left to change it back; one a level still sets must keep it.
 */
@RunWith(RobolectricTestRunner::class)
class AppPrefsTest {

    private val sp = RuntimeEnvironment.getApplication().getSharedPreferences("app-prefs-test", Context.MODE_PRIVATE)

    @Before
    fun empty() {
        sp.edit { clear() }
    }

    @Test
    fun settingsWithoutAUiGoBackToTheirDefaultsOnceAndAgainAfterAnImport() {
        sp.edit {
            putBoolean("ignore_system_cursor", true)
            putString("lang_switch_key_behavior", LangSwitchBehavior.ToggleActivate.name)
            putInt("candidates_window_radius", 12)
        }
        val prefs = AppPrefs(sp)
        prefs.resetHiddenSettings()
        assertFalse(prefs.advanced.ignoreSystemCursor.getValue())
        assertEquals(LangSwitchBehavior.Enumerate, prefs.keyboard.langSwitchKeyBehavior.getValue())
        assertEquals(0, prefs.candidates.windowRadius.getValue())
        sp.edit { putBoolean("ignore_system_cursor", true) }
        prefs.resetHiddenSettings()
        assertTrue("once", prefs.advanced.ignoreSystemCursor.getValue())
        prefs.resetHiddenSettings(again = true)
        assertFalse(prefs.advanced.ignoreSystemCursor.getValue())
    }

    // the real application: ThemePrefs, which has the keyboard's size levels, is read through ThemeManager
    @Test
    @Config(application = FcitxApplication::class)
    fun whatTheSettingsPageStillSetsIsKept() {
        val prefs = AppPrefs(sp)
        val categories = listOf(prefs.keyboard, prefs.candidates, prefs.clipboard, prefs.symbols, prefs.advanced)
        // AppPrefs' keys alone: ThemeManager.prefs is registered with the application's AppPrefs, not this one.
        // ThemePrefsTest and aCategoryRegisteredAfterTheResetIsResetAsItRegisters check that its reset keeps what its levels set
        val own = categories.flatMapTo(HashSet()) { it.managedPreferences.keys }
        // what every level sets, one off its default: the keyboard's size on the theme page, the long press, the vibration, the sound
        val levels = (categories + ThemeManager.prefs)
            .flatMap { it.managedPreferencesUi }
            .filterIsInstance<ManagedPreferenceUi.Levels>()
            .flatMap { it.keys }
            .filter { it.first in own }
            .associate { (key, default) -> key to default + 1 }
        assertTrue(levels.keys.containsAll(listOf("keyboard_height_percent", "keyboard_long_press_delay")))
        sp.edit {
            levels.forEach { (key, value) -> putInt(key, value) }
            putBoolean("popup_on_key_press", false)
        }
        prefs.resetHiddenSettings()
        assertEquals(levels, levels.mapValues { sp.getInt(it.key, -1) })
        assertFalse("shown", prefs.keyboard.popupOnKeyPress.getValue())
    }

    // ThemeManager registers ThemePrefs after the reset at the start: what it hides goes back then
    @Test
    @Config(application = FcitxApplication::class)
    fun aCategoryRegisteredAfterTheResetIsResetAsItRegisters() {
        sp.edit {
            putString("one_handed_side", "Left")
            putInt("keyboard_side_padding", 9)
        }
        val prefs = AppPrefs(sp)
        prefs.resetHiddenSettings()
        val theme = prefs.registerProvider(::ThemePrefs)
        assertEquals(OneHanded.Right, theme.oneHandedSide.getValue())
        assertEquals("a level sets it", 9, sp.getInt("keyboard_side_padding", -1))
        // a later start, the reset done before it: nothing registered then is reset
        sp.edit { putString("one_handed_side", "Left") }
        val later = AppPrefs(sp)
        later.resetHiddenSettings()
        assertEquals(OneHanded.Left, later.registerProvider(::ThemePrefs).oneHandedSide.getValue())
    }

    // 0.4's glide typing switch (glide_typing) is this version's 滑行输入 switch, under the same key:
    // a 0.4 user's, or a 0.4 backup's, carries over as it is; 笔画筛选 off
    @Test
    @Uat("ST-43")
    fun glideTypingCarriesOverAsItIs() {
        class Row(val why: String, val stored: Boolean?)
        assertRows(listOf(Row("0.4 on", true), Row("0.4 off", false), Row("never stored", null)), { it.why }) { row ->
            sp.edit {
                clear()
                if (row.stored != null) putBoolean(AppPrefs.GLIDE_TYPING, row.stored)
            }
            assertEquals(row.stored == true, AppPrefs(sp).keyboard.glideTyping.getValue())
            assertFalse(AppPrefs(sp).keyboard.strokeFilter.getValue())
        }
    }

    // contract: the keyboard choices are stored by the constant's name under keys that never change:
    // one renamed would put every user's back to the full keyboard; a value this version does not know
    // (a later keyboard's) reads as the default
    @Test
    fun theKeyboardChoicesAreStoredByNamesThatNeverChange() {
        class Row(
            val why: String,
            val names: List<String>,
            val entries: List<Enum<*>>,
            val key: String,
            val storedKey: String,
            val set: (AppPrefs) -> Unit,
            val stored: String,
            val unknown: String,
            val read: (AppPrefs) -> Enum<*>,
            val default: Enum<*>,
        )
        assertRows(
            listOf(
                // 0.4's Full and Keys14 as they were; Keys18 and NineKeys since I1
                Row(
                    "全拼's keyboard", listOf("Full", "Keys18", "Keys14", "NineKeys", "ZhuyinNine", "ZhuyinFull"), PinyinKeyboard.entries, "pinyin_keyboard", AppPrefs.PINYIN_KEYBOARD,
                    { it.keyboard.pinyinKeyboard.setValue(PinyinKeyboard.Keys14) }, "Keys14", "Keys16",
                    { it.keyboard.pinyinKeyboard.getValue() }, PinyinKeyboard.Full,
                ),
                Row(
                    "双拼's keyboard", listOf("Full", "Keys18", "Keys14", "NineKeys"), ShuangpinKeyboard.entries, "shuangpin_keyboard", AppPrefs.SHUANGPIN_KEYBOARD,
                    { it.keyboard.shuangpinKeyboard.setValue(ShuangpinKeyboard.NineKeys) }, "NineKeys", "Keys16",
                    { it.keyboard.shuangpinKeyboard.getValue() }, ShuangpinKeyboard.Full,
                ),
                Row(
                    "English's keyboard", listOf("Full", "NineKeys"), EnglishKeyboard.entries, "english_keyboard", AppPrefs.ENGLISH_KEYBOARD,
                    { it.keyboard.englishKeyboard.setValue(EnglishKeyboard.NineKeys) }, "NineKeys", "Keys14",
                    { it.keyboard.englishKeyboard.getValue() }, EnglishKeyboard.Full,
                ),
            ),
            { it.why },
        ) { row ->
            sp.edit { clear() }
            assertEquals(row.names, row.entries.map { it.name })
            assertEquals(row.key, row.storedKey)
            assertEquals(row.default, row.read(AppPrefs(sp)))
            row.set(AppPrefs(sp))
            assertEquals(row.stored, sp.getString(row.key, null))
            sp.edit { putString(row.key, row.unknown) }
            assertEquals(row.default, row.read(AppPrefs(sp)))
        }
    }

    // each pair of ways a SwipeLayers can be made is a choice of the setting's own: SwipeLayersChoice.of
    // falls back to the default only for one no choice lists, which would then be lost unsaid
    @Test
    fun everySwipeLayersThatCanBeMadeIsAChoice() {
        for (up in SwipeLayers.Layer.entries) for (down in SwipeLayers.Layer.entries) {
            val layers = runCatching { SwipeLayers(up, down) }.getOrNull() ?: continue
            assertEquals("$up/$down", layers, SwipeLayersChoice.of(layers).layers)
        }
    }

    // 0.5's 划动输入标点符号及数字 becomes 滑动: the symbol the way it was, shortcuts the other way, off all off
    @Test
    fun theOldSwipeSettingBecomesTheTwoWays() {
        // each row from an emptied store of its own: nothing one leaves is read by the next
        fun row(why: String, stored: SharedPreferences.Editor.() -> Unit, now: SwipeLayersChoice) =
            why to {
                sp.edit { clear(); stored() }
                val prefs = AppPrefs(sp).apply { dropRetired() }
                assertEquals(now, prefs.keyboard.swipeLayers.getValue())
                assertFalse("the old setting dropped", sp.contains("swipe_symbol_behavior"))
            }
        assertNamedRows(
            row("Down: the symbol down, shortcuts up", { putString("swipe_symbol_behavior", "Down") }, SwipeLayersChoice.ShortcutUpSymbolDown),
            row("Up: the symbol up, shortcuts down", { putString("swipe_symbol_behavior", "Up") }, SwipeLayersChoice.SymbolUpShortcutDown),
            row("Disabled: all off", { putString("swipe_symbol_behavior", "Disabled") }, SwipeLayersChoice.Off),
            row(
                "one set already is kept",
                { putString("swipe_symbol_behavior", "Up"); putString("swipe_layers", SwipeLayersChoice.Off.name) },
                SwipeLayersChoice.Off,
            ),
            // the bug: the old key read as a String whatever it held, so a bad backup storing it as an
            // Int threw a ClassCastException in dropRetired at every start; now the default, no crash
            row("stored as another type (a bad backup): the default, no crash at every start", { putInt("swipe_symbol_behavior", 1) }, SwipeLayersChoice.ShortcutUpSymbolDown),
        )
    }

    // an import or a backup with the two in one place: the symbol stored where it reads, at the start after
    @Test
    @Uat("KB-66")
    fun theSymbolStoredInTheShortcutsPlaceStoredWhereItReads() {
        for ((stored, now) in listOf(
            KeyMarkPosition.TopLeft to KeyMarkPosition.BottomLeft,
            KeyMarkPosition.TopRight to KeyMarkPosition.BottomRight,
            KeyMarkPosition.Bottom to KeyMarkPosition.TopRight,
            KeyMarkPosition.None to KeyMarkPosition.None,
        )) {
            sp.edit { clear(); putString("punctuation_position", stored.name); putString("shortcut_mark_position", stored.name) }
            val prefs = AppPrefs(sp)
            prefs.dropRetired()
            assertEquals(stored.name, now, prefs.keyboard.punctuationPosition.getValue())
            assertEquals(stored.name, stored, prefs.keyboard.shortcutMarkPosition.getValue())
        }
        // apart already: as stored
        sp.edit { clear(); putString("punctuation_position", "BottomLeft"); putString("shortcut_mark_position", "TopLeft") }
        AppPrefs(sp).apply { dropRetired() }.let { assertEquals(KeyMarkPosition.BottomLeft, it.keyboard.punctuationPosition.getValue()) }
    }

    // the old drawing's settings (the redraw's switch of 0.6's betas, vivo's touch routing, 扩大按键触摸区), stored
    // as either type: dropped, read by nothing, no crash
    @Test
    fun theOldDrawingsSettingsStoredAreDropped() {
        for (key in listOf("canvas_keyboard", "vivo_keypress_workaround", "expand_keypress_area")) {
            for (put in listOf<SharedPreferences.Editor.() -> Unit>({ putBoolean(key, true) }, { putString(key, "x") })) {
                sp.edit { clear(); put() }
                AppPrefs(sp).dropRetired()
                assertFalse(key, sp.contains(key))
            }
        }
    }
}
