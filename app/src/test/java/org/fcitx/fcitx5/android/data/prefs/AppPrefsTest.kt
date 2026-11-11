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
import org.fcitx.fcitx5.android.input.keyboard.KeyDrawing
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.keyboard.KeyMarks
import org.fcitx.fcitx5.android.input.keyboard.LangSwitchBehavior
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayers
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayersChoice
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.input.shortcut.SwipeMigration
import org.fcitx.fcitx5.android.input.shortcut.SwipeOrientation
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

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
        // and 跟随主题 of the keyboard's size, which a level list sets too
        val follows = (categories + ThemeManager.prefs)
            .flatMap { it.managedPreferencesUi }
            .filterIsInstance<ManagedPreferenceUi.Levels>()
            .mapNotNull { it.follow?.first }
            .filter { it in own }
        assertEquals(3, follows.size)
        sp.edit {
            levels.forEach { (key, value) -> putInt(key, value) }
            follows.forEach { putBoolean(it, false) }
            putBoolean("popup_on_key_press", false)
        }
        prefs.resetHiddenSettings()
        assertEquals(levels, levels.mapValues { sp.getInt(it.key, -1) })
        assertTrue(follows.none { sp.getBoolean(it, true) })
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

    // 0.5's two switches 滑行输入 (glide_typing, 0.4's key too) and 笔画筛选 (stroke_filter) as the one list
    // 在键上划: a 0.5 user's, or a backup's read at the next start, carries over; both old keys dropped
    @Test
    @Uat("ST-43")
    fun theTwoSwitchesMoveOverToKeyDrawing() {
        fun row(why: String, stored: SharedPreferences.Editor.() -> Unit, now: KeyDrawing) =
            why to {
                sp.edit { clear(); stored() }
                val prefs = AppPrefs(sp).apply { dropRetired() }
                assertEquals(now, prefs.keyboard.keyDrawing.getValue())
                assertFalse("the old switches dropped", sp.contains("glide_typing") || sp.contains("stroke_filter"))
            }
        assertNamedRows(
            row("glide on", { putBoolean("glide_typing", true); putBoolean("stroke_filter", false) }, KeyDrawing.Glide),
            row("strokes on", { putBoolean("glide_typing", false); putBoolean("stroke_filter", true) }, KeyDrawing.StrokeFilter),
            row("both off", { putBoolean("glide_typing", false); putBoolean("stroke_filter", false) }, KeyDrawing.Off),
            // the page never stored both: a backup, edited by hand; glide, the older and wider
            row("both on: glide", { putBoolean("glide_typing", true); putBoolean("stroke_filter", true) }, KeyDrawing.Glide),
            row("0.4's glide alone", { putBoolean("glide_typing", true) }, KeyDrawing.Glide),
            row("never stored: off", {}, KeyDrawing.Off),
            row("one set already is kept", { putBoolean("glide_typing", true); putString("key_drawing", "StrokeFilter") }, KeyDrawing.StrokeFilter),
            row("stored as another type: off, no crash at every start", { putString("glide_typing", "x") }, KeyDrawing.Off),
        )
    }

    // a 0.5 backup imported: the start after it resets the hidden settings (the import's marker) and then
    // drops the retired ones, in FcitxApplication.onCreate's order; the two switches still move over
    @Test
    fun anImportedBackupsSwitchesMoveOverAtTheStartAfter() {
        sp.edit { clear(); putBoolean("glide_typing", false); putBoolean("stroke_filter", true) }
        val marker = File.createTempFile("imported", null)
        val prefs = AppPrefs(sp)
        FcitxApplication.resetHiddenSettings(prefs, directBoot = false) { marker }
        prefs.dropRetired()
        assertFalse("the marker gone", marker.exists())
        assertEquals(KeyDrawing.StrokeFilter, prefs.keyboard.keyDrawing.getValue())
        assertFalse(sp.contains("glide_typing") || sp.contains("stroke_filter"))
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
                // 在键上划 since 0.6; Handwriting stored before it is offered (a later version's backup)
                Row(
                    "在键上划", listOf("Glide", "StrokeFilter", "Handwriting", "Off"), KeyDrawing.entries, "key_drawing", AppPrefs.KEY_DRAWING,
                    { it.keyboard.keyDrawing.setValue(KeyDrawing.StrokeFilter) }, "StrokeFilter", "Strokes",
                    { it.keyboard.keyDrawing.getValue() }, KeyDrawing.Off,
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

    // 0.5's 划动输入标点符号及数字 becomes 滑动, and that 0.6's slots: the symbol the way it was, shortcuts the other
    // way, off all off
    @Test
    fun theOldSwipeSettingBecomesTheTwoWays() {
        // each row from an emptied store of its own: nothing one leaves is read by the next
        fun row(why: String, stored: SharedPreferences.Editor.() -> Unit, now: SwipeLayersChoice) =
            why to {
                sp.edit { clear(); stored() }
                val prefs = AppPrefs(sp).apply { dropRetired() }
                val want = SwipeMigration.of(now.layers, upgraded = true, KeyShortcuts.Empty, KeyMarks.At.TopRight, KeyMarks.At.TopLeft)
                assertEquals(want.slots.orientation, SwipeSlots.parse(prefs.internal.keySwipes.getValue()).orientation)
                assertEquals(want.up to want.down, prefs.keyboard.swipeUp.getValue() to prefs.keyboard.swipeDown.getValue())
                assertFalse("the old setting dropped", sp.contains("swipe_symbol_behavior"))
                assertFalse("滑动 dropped too", sp.contains("swipe_layers"))
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

    // 0.5's 滑动, shortcuts and two places become 0.6's slots, ways and places, a swipe doing what it did, each
    // mark where it was (060-swipe-slots); the old keys dropped
    @Test
    fun theOldSwipeSettingsBecomeTheSlots() {
        sp.edit {
            clear()
            putInt("pid", 1)
            putString("swipe_layers", SwipeLayersChoice.SymbolDownOnly.name)
            putString("key_shortcuts", "full/q\ttext:你好\nfn/space.up\tnone")
            putString("punctuation_position", KeyMarkPosition.BottomRight.name)
            putString("shortcut_mark_position", KeyMarkPosition.TopLeft.name)
        }
        val prefs = AppPrefs(sp).apply { dropRetired() }
        val slots = SwipeSlots.parse(prefs.internal.keySwipes.getValue())
        assertEquals(SwipeOrientation.FirstDown, slots.orientation)
        assertEquals("the shortcut the way the symbol is not", SwipeSlot.Text("你好"), slots.resolve("full/q", up = true))
        assertEquals(SwipeSlot.First, slots.resolve("full/q", up = false))
        assertEquals(SwipeSlot.Empty, slots.resolve("fn/space", up = true))
        assertFalse(prefs.keyboard.swipeUp.getValue())
        assertTrue(prefs.keyboard.swipeDown.getValue())
        assertEquals("the symbol's place down, where it goes", KeyMarkPosition.BottomRight, prefs.keyboard.swipeDownPosition.getValue())
        assertEquals(KeyMarkPosition.TopLeft, prefs.keyboard.swipeUpPosition.getValue())
        for (old in listOf("swipe_layers", "key_shortcuts", "punctuation_position", "shortcut_mark_position")) assertFalse(old, sp.contains(old))
        // read again: nothing more to do, nothing changed
        val stored = sp.all
        AppPrefs(sp).dropRetired()
        assertEquals(stored, sp.all)
    }

    // a 0.6 beta's shortcuts (beta1 and on: the symbol up, shortcuts down) holding 滑选: the same slots, 滑选 kept
    @Test
    fun aBetasSwipeSelectStaysInItsSlot() {
        sp.edit {
            clear()
            putInt("pid", 1)
            putString("swipe_layers", SwipeLayersChoice.SymbolUpShortcutDown.name)
            putString("key_shortcuts", "full/q\tswipe_select\nfn/space.up\tswipe_select")
        }
        val slots = SwipeSlots.parse(AppPrefs(sp).apply { dropRetired() }.internal.keySwipes.getValue())
        val select = SwipeSlot.Command(KeyCommand.SwipeSelect)
        assertEquals(SwipeOrientation.FirstUp, slots.orientation)
        assertEquals(select, slots.resolve("full/q", up = false))
        assertEquals(SwipeSlot.First, slots.resolve("full/q", up = true))
        assertEquals(select, slots.resolve("fn/space", up = true))
    }

    // a newer version's slots (its backup restored here, a downgrade): read as the presets, never written over
    @Test
    fun aNewerVersionsSlotsAreLeftAsTheyAre() {
        val newer = "@2\tfirst-down\nfull/a.up\tcopy"
        sp.edit { clear(); putInt("pid", 1); putString("key_swipes", newer); putString("swipe_layers", "Off") }
        val prefs = AppPrefs(sp).apply { dropRetired() }
        assertEquals(newer, prefs.internal.keySwipes.getValue())
        assertEquals(SwipeSlots.Default, SwipeSlots.parse(prefs.internal.keySwipes.getValue()))
    }

    // a new install: the defaults (the first up, its shortcut down), none of 0.5's; an upgrade that never set
    // 滑动 had 0.5's default (shortcuts up, the symbol down), and keeps it
    @Test
    fun aNewInstallHasTheNewDefaultsAnUpgradeItsOld() {
        sp.edit { clear() }
        val fresh = AppPrefs(sp).apply { dropRetired() }
        assertEquals(SwipeSlots.Default, SwipeSlots.parse(fresh.internal.keySwipes.getValue()))
        assertEquals(KeyMarkPosition.TopRight, fresh.keyboard.swipeUpPosition.getValue())
        sp.edit { clear(); putInt("pid", 1) }
        val upgraded = AppPrefs(sp).apply { dropRetired() }
        assertEquals(SwipeOrientation.FirstDown, SwipeSlots.parse(upgraded.internal.keySwipes.getValue()).orientation)
        assertEquals("the shortcut up, where its marks were", KeyMarkPosition.TopLeft, upgraded.keyboard.swipeUpPosition.getValue())
        assertEquals(KeyMarkPosition.TopRight, upgraded.keyboard.swipeDownPosition.getValue())
        // 0.6's set already (a 0.5 backup's keys merged in by hand): kept, the old ones dropped
        sp.edit { clear(); putString("key_swipes", SwipeSlots.Default.with("full/a", true, SwipeSlot.Empty).serialize()); putString("swipe_layers", "Off") }
        val kept = AppPrefs(sp).apply { dropRetired() }
        assertEquals(SwipeSlot.Empty, SwipeSlots.parse(kept.internal.keySwipes.getValue()).resolve("full/a", true))
        assertTrue(kept.keyboard.swipeUp.getValue())
        assertFalse(sp.contains("swipe_layers"))
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
            assertEquals(stored.name, now, prefs.keyboard.swipeUpPosition.getValue())
            assertEquals(stored.name, stored, prefs.keyboard.swipeDownPosition.getValue())
        }
        // 0.6's own two stored in one place: the up one moved off the down one's, as the page reads it
        sp.edit { clear(); putString("key_swipes", ""); putString("swipe_up_position", "TopLeft"); putString("swipe_down_position", "TopLeft") }
        AppPrefs(sp).apply { dropRetired() }.let { assertEquals(KeyMarkPosition.BottomLeft, it.keyboard.swipeUpPosition.getValue()) }
        // apart already: as stored
        sp.edit { clear(); putString("punctuation_position", "BottomLeft"); putString("shortcut_mark_position", "TopLeft") }
        AppPrefs(sp).apply { dropRetired() }.let { assertEquals(KeyMarkPosition.BottomLeft, it.keyboard.swipeUpPosition.getValue()) }
    }

    // the old drawing's settings (the redraw's switch of 0.6's betas, vivo's touch routing, 扩大按键触摸区) and the
    // voice's 整理 switch (voice_polish), stored as either type: dropped, read by nothing, no crash
    @Test
    fun theRetiredSettingsStoredAreDropped() {
        for (key in listOf("canvas_keyboard", "vivo_keypress_workaround", "expand_keypress_area", "voice_polish")) {
            for (put in listOf<SharedPreferences.Editor.() -> Unit>({ putBoolean(key, true) }, { putString(key, "x") })) {
                sp.edit { clear(); put() }
                AppPrefs(sp).dropRetired()
                assertFalse(key, sp.contains(key))
            }
        }
    }
}
