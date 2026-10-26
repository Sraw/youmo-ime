/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.Context
import androidx.core.content.edit
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.input.keyboard.LangSwitchBehavior
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
            putBoolean("key_ripple_effect", true)
            putInt("key_radius", 9)
        }
        val prefs = AppPrefs(sp)
        prefs.resetHiddenSettings()
        val theme = prefs.registerProvider(::ThemePrefs)
        assertFalse(theme.keyRippleEffect.getValue())
        assertEquals("a level sets it", 9, theme.keyRadius.getValue())
        // a later start, the reset done before it: nothing registered then is reset
        sp.edit { putBoolean("key_ripple_effect", true) }
        val later = AppPrefs(sp)
        later.resetHiddenSettings()
        assertTrue(later.registerProvider(::ThemePrefs).keyRippleEffect.getValue())
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
                Row(
                    "全拼's keyboard", listOf("Full", "Keys14"), PinyinKeyboard.entries, "pinyin_keyboard", AppPrefs.PINYIN_KEYBOARD,
                    { it.keyboard.pinyinKeyboard.setValue(PinyinKeyboard.Keys14) }, "Keys14", "Keys18",
                    { it.keyboard.pinyinKeyboard.getValue() }, PinyinKeyboard.Full,
                ),
                Row(
                    "双拼's keyboard", listOf("Full", "Keys18", "NineKeys"), ShuangpinKeyboard.entries, "shuangpin_keyboard", AppPrefs.SHUANGPIN_KEYBOARD,
                    { it.keyboard.shuangpinKeyboard.setValue(ShuangpinKeyboard.NineKeys) }, "NineKeys", "Keys14",
                    { it.keyboard.shuangpinKeyboard.getValue() }, ShuangpinKeyboard.Full,
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
}
