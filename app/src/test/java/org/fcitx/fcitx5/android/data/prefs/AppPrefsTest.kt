/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows as assertNamedRows
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.input.bar.ToolbarConfig
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyDrawing
import org.fcitx.fcitx5.android.input.keyboard.StrokeLift
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.keyboard.OfferedLayouts
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayers
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayersChoice
import org.fcitx.fcitx5.android.input.keys.ScrollListStore
import org.fcitx.fcitx5.android.input.shortcut.SwipeOrientation
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.keys.ScrollDefaults
import org.fcitx.fcitx5.android.scene.keys.ScrollItem
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

@RunWith(RobolectricTestRunner::class)
class AppPrefsTest {

    private val sp = RuntimeEnvironment.getApplication().getSharedPreferences("app-prefs-test", Context.MODE_PRIVATE)

    @Before
    fun empty() {
        sp.edit { clear() }
    }

    // a restore that could not delete the backup's punctuation table: the backup's mark would stop the start retrying
    @Test
    fun anImportClearsThePunctuationMark() {
        sp.edit { putBoolean("punctuation_reset_061", true) }
        val prefs = AppPrefs(sp)
        prefs.afterImport()
        assertFalse(prefs.internal.punctuationReset061.getValue())
    }

    // the side 单手模式 was last at is app state: kept across a backup import
    @Test
    @Config(application = FcitxApplication::class)
    @Uat("PR-53")
    fun theOneHandedSideRememberedIsKeptAfterAnImport() {
        sp.edit { putString("one_handed_side", "Left") }
        val prefs = AppPrefs(sp)
        val theme = prefs.registerProvider(::ThemePrefs)
        prefs.afterImport()
        assertEquals(OneHanded.Left, theme.oneHandedSide.getValue())
        // Off is no side: read as the default
        sp.edit { putString("one_handed_side", "Off") }
        assertEquals(OneHanded.Right, theme.oneHandedSide.getValue())
    }

    // 0.5's switches 滑行输入 (glide_typing) and 笔画筛选 (stroke_filter) become the one list 在键上划; old keys dropped
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
            // never stored both by the page (a hand-edited backup): glide, the older and wider
            row("both on: glide", { putBoolean("glide_typing", true); putBoolean("stroke_filter", true) }, KeyDrawing.Glide),
            row("0.4's glide alone", { putBoolean("glide_typing", true) }, KeyDrawing.Glide),
            row("never stored: off", {}, KeyDrawing.Off),
            row("one set already is kept", { putBoolean("glide_typing", true); putString("key_drawing", "StrokeFilter") }, KeyDrawing.StrokeFilter),
            row("stored as another type: off, no crash at every start", { putString("glide_typing", "x") }, KeyDrawing.Off),
        )
    }

    // 0.6.0's 在键上划 = 键盘手写 becomes 关, the toolbar's 手写 pinned (not on) and the user told once; others left
    @Test
    @Uat("PR-56")
    fun zeroSixsKeyHandwritingIsOffWithTheToolbarsHandwritingPinned() {
        fun row(why: String, drawing: String, bar: String, now: KeyDrawing, told: Boolean, barNow: String) =
            why to {
                sp.edit { clear(); putString(AppPrefs.KEY_DRAWING, drawing); if (bar.isNotEmpty()) putString("toolbar_buttons", bar) }
                val prefs = AppPrefs(sp).apply { dropRetired() }
                assertEquals(now, prefs.keyboard.keyDrawing.getValue())
                assertEquals(told, prefs.internal.keyHandwritingUnseen.getValue())
                assertFalse("not turned on", prefs.internal.handwritingOpen.getValue())
                assertEquals(barNow, prefs.internal.toolbarButtons.getValue())
            }
        assertNamedRows(
            row("键盘手写, the bar never arranged", "Handwriting", "", KeyDrawing.Off, true, ""),
            row("键盘手写, the bar arranged", "Handwriting", "undo,|,voice,hide", KeyDrawing.Off, true,
                ToolbarConfig.parse("undo,|,voice,hide").withHandwritingPinned().serialize()),
            row("滑行输入: as it was, but for 手写 put on a bar from before it", "Glide", "undo,|,voice,hide", KeyDrawing.Glide, false,
                ToolbarConfig.parse("undo,|,voice,hide").withHandwritingOffered().serialize()),
        )
    }

    // a 0.6.0-arranged bar gets 手写 after 表情 at every start (a restored backup too); the first test build's only once; one
    // the user removed since stays; never arranged, nothing written
    @Test
    @Uat("TB-66")
    fun aBarArrangedBeforeHandwritingGetsIt() {
        val zeroSix = "undo,redo,cursor_move,clipboard,|,emoji,voice,hide"
        val offered = ToolbarConfig.parse(zeroSix).withHandwritingOffered().serialize()
        fun start(bar: String, looked: Boolean = false): String {
            sp.edit {
                clear()
                if (bar.isNotEmpty()) putString("toolbar_buttons", bar)
                putBoolean("toolbar_handwriting_looked", looked)
            }
            return AppPrefs(sp).apply { dropRetired() }.internal.toolbarButtons.getValue()
        }
        assertTrue(offered, offered.startsWith("undo,redo,cursor_move,clipboard,") && offered.endsWith(",|,emoji,handwriting,voice,hide"))
        assertEquals("0.6.0's", offered, start(zeroSix))
        assertEquals("0.6.0's restored after a start", offered, start(zeroSix, looked = true))
        val beta = ToolbarConfig.parse(zeroSix).serialize()
        assertEquals("the first test build's", offered, start(beta))
        assertEquals("unticked on the toolbar since", beta, start(beta, looked = true))
        val off = offered.replace("handwriting", "-handwriting")
        assertEquals("unticked at the right", off, start(off))
        assertEquals("never arranged", "", start(""))
        val prefs = AppPrefs(sp).apply { dropRetired() }
        assertTrue("looked at once", prefs.internal.toolbarHandwritingLooked.getValue())
    }

    // a 0.5 backup imported: the next start reads the marker, then drops retired keys in FcitxApplication.onCreate's order
    @Test
    fun anImportedBackupsSwitchesMoveOverAtTheStartAfter() {
        sp.edit { clear(); putBoolean("glide_typing", false); putBoolean("stroke_filter", true) }
        val marker = File.createTempFile("imported", null)
        val prefs = AppPrefs(sp)
        FcitxApplication.afterImport(prefs, directBoot = false) { marker }
        prefs.dropRetired()
        assertFalse("the marker gone", marker.exists())
        assertEquals(KeyDrawing.StrokeFilter, prefs.keyboard.keyDrawing.getValue())
        assertFalse(sp.contains("glide_typing") || sp.contains("stroke_filter"))
    }

    // an upgrade, or a backup from before the key restored: each input method's current layout alone, written once
    @Test
    @Uat("ST-226", "ST-229")
    fun theLayoutsOfferedAreEachInputMethodsCurrentOneTillAnyIsStored() {
        sp.edit {
            putString(AppPrefs.PINYIN_KEYBOARD, PinyinKeyboard.Custom.name)
            putString(AppPrefs.PINYIN_KEYBOARD_LAYOUT, "t1a2-k18")
            // English back on its own keys: the layout it once chose is not listed
            putString(AppPrefs.ENGLISH_KEYBOARD_LAYOUT, "my-qwerty")
            putString(AppPrefs.TABLE_KEYBOARD_LAYOUT, "my-qwerty")
        }
        val prefs = AppPrefs(sp)
        // read in a direct boot (the copy has no key): derived, nothing written
        assertEquals(listOf("t1a2-k18"), prefs.keyboard.offeredLayouts().of(InputMethodCatalog.PINYIN))
        assertFalse(sp.contains(AppPrefs.KEYBOARD_LAYOUTS_OFFERED))
        prefs.dropRetired()
        assertEquals("@1\npinyin t1a2-k18\ntable my-qwerty", sp.getString(AppPrefs.KEYBOARD_LAYOUTS_OFFERED, null))
        // what is stored is kept: none on, the current one listed all the same
        sp.edit { putString(AppPrefs.KEYBOARD_LAYOUTS_OFFERED, OfferedLayouts.HEADER) }
        AppPrefs(sp).dropRetired()
        assertEquals(OfferedLayouts.HEADER, sp.getString(AppPrefs.KEYBOARD_LAYOUTS_OFFERED, null))
    }

    // where none is stored yet, the layout a choice replaces stays listed; a later version's is written over at the change
    @Test
    fun choosingALayoutTurnsItOnAndTheOneItReplacesStaysOn() {
        sp.edit {
            putString(AppPrefs.PINYIN_KEYBOARD, PinyinKeyboard.Custom.name)
            putString(AppPrefs.PINYIN_KEYBOARD_LAYOUT, "t1a2-k18")
            putString(AppPrefs.KEYBOARD_LAYOUTS_OFFERED, "@2\npinyin x")
        }
        val keyboard = AppPrefs(sp).keyboard
        assertEquals(listOf("t1a2-k18"), keyboard.offeredLayouts().of(InputMethodCatalog.PINYIN))
        keyboard.setImKeyboard(InputMethodCatalog.PINYIN, ImKeyboard.CUSTOM, "my-k14")
        assertEquals("@1\npinyin t1a2-k18 my-k14", sp.getString(AppPrefs.KEYBOARD_LAYOUTS_OFFERED, null))
        // a keyboard of the app's chosen: the group left as it is
        keyboard.setImKeyboard(InputMethodCatalog.PINYIN, ImKeyboard.FULL)
        assertEquals(listOf("t1a2-k18", "my-k14"), keyboard.offeredLayouts().of(InputMethodCatalog.PINYIN))
    }

    // keyboard choices are stored by constant name under fixed keys: a rename would reset everyone; an unknown value
    // (a later keyboard's) reads as default
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
                // 0.4's Full and Keys14; Keys18 and NineKeys later; Custom since 0.6.1
                Row(
                    "全拼's keyboard", listOf("Full", "Keys18", "Keys14", "NineKeys", "ZhuyinNine", "ZhuyinFull", "Custom"), PinyinKeyboard.entries, "pinyin_keyboard", AppPrefs.PINYIN_KEYBOARD,
                    { it.keyboard.pinyinKeyboard.setValue(PinyinKeyboard.Keys14) }, "Keys14", "Keys16",
                    { it.keyboard.pinyinKeyboard.getValue() }, PinyinKeyboard.Full,
                ),
                Row(
                    "双拼's keyboard", listOf("Full", "Keys18", "Keys14", "NineKeys", "Custom"), ShuangpinKeyboard.entries, "shuangpin_keyboard", AppPrefs.SHUANGPIN_KEYBOARD,
                    { it.keyboard.shuangpinKeyboard.setValue(ShuangpinKeyboard.NineKeys) }, "NineKeys", "Keys16",
                    { it.keyboard.shuangpinKeyboard.getValue() }, ShuangpinKeyboard.Full,
                ),
                // 在键上划 since 0.6; 0.6.0's Handwriting moved over at start (migrateKeyHandwriting)
                Row(
                    "在键上划", listOf("Glide", "StrokeFilter", "Off"), KeyDrawing.entries, "key_drawing", AppPrefs.KEY_DRAWING,
                    { it.keyboard.keyDrawing.setValue(KeyDrawing.StrokeFilter) }, "StrokeFilter", "Strokes",
                    { it.keyboard.keyDrawing.getValue() }, KeyDrawing.Off,
                ),
                Row(
                    "笔画筛选灵敏度", listOf("High", "Medium", "Low"), StrokeLift.entries, "stroke_lift", AppPrefs.STROKE_LIFT,
                    { it.keyboard.strokeLift.setValue(StrokeLift.High) }, "High", "Extreme",
                    { it.keyboard.strokeLift.getValue() }, StrokeLift.Medium,
                ),
                Row(
                    "English's keyboard", listOf("Full", "NineKeys", "Custom"), EnglishKeyboard.entries, "english_keyboard", AppPrefs.ENGLISH_KEYBOARD,
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

    // every SwipeLayers pair must be a listed choice: SwipeLayersChoice.of would otherwise fall back silently
    @Test
    fun everySwipeLayersThatCanBeMadeIsAChoice() {
        for (up in SwipeLayers.Layer.entries) for (down in SwipeLayers.Layer.entries) {
            val layers = runCatching { SwipeLayers(up, down) }.getOrNull() ?: continue
            assertEquals("$up/$down", layers, SwipeLayersChoice.of(layers).layers)
        }
    }

    // 0.5's 划动输入标点符号及数字 becomes 0.6's slots: the symbol the way it was, shortcuts the other way, off all off
    @Test
    fun theOldSwipeSettingBecomesTheTwoWays() {
        // each row from its own emptied store
        fun row(why: String, stored: SharedPreferences.Editor.() -> Unit, orientation: SwipeOrientation, up: Boolean, down: Boolean) =
            why to {
                sp.edit { clear(); stored() }
                val prefs = AppPrefs(sp).apply { dropRetired() }
                // written out, not derived via SwipeMigration: 0.5's meaning of each direction checked independently
                assertEquals(orientation, SwipeSlots.parse(prefs.internal.keySwipes.getValue()).orientation)
                assertEquals(up to down, prefs.keyboard.swipeUp.getValue() to prefs.keyboard.swipeDown.getValue())
                assertFalse("the old setting dropped", sp.contains("swipe_symbol_behavior"))
                assertFalse("滑动 dropped too", sp.contains("swipe_layers"))
            }
        assertNamedRows(
            row("Down: the symbol down, shortcuts up", { putString("swipe_symbol_behavior", "Down") }, SwipeOrientation.FirstDown, up = true, down = true),
            row("Up: the symbol up, shortcuts down", { putString("swipe_symbol_behavior", "Up") }, SwipeOrientation.FirstUp, up = true, down = true),
            row("Disabled: all off", { putString("swipe_symbol_behavior", "Disabled") }, SwipeOrientation.FirstDown, up = false, down = false),
            row(
                "one set already is kept",
                { putString("swipe_symbol_behavior", "Up"); putString("swipe_layers", SwipeLayersChoice.Off.name) },
                SwipeOrientation.FirstDown,
                up = false,
                down = false,
            ),
            // the old key read as a String whatever it held, so an Int from a bad backup threw in dropRetired at every start
            row("stored as another type (a bad backup): the default, no crash at every start", { putInt("swipe_symbol_behavior", 1) }, SwipeOrientation.FirstDown, up = true, down = true),
        )
    }

    // 0.5's 滑动, shortcuts and two places become 0.6's slots, ways and places, each mark where it was; old keys dropped
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
        // read again: nothing more to do
        val stored = sp.all
        AppPrefs(sp).dropRetired()
        assertEquals(stored, sp.all)
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

    // a new install gets the defaults; an upgrade that never set 滑动 keeps 0.5's default (shortcuts up, symbol down)
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
        // 0.6's set already (0.5 keys merged by hand): kept, old ones dropped
        sp.edit { clear(); putString("key_swipes", SwipeSlots.Default.with("full/a", true, SwipeSlot.Empty).serialize()); putString("swipe_layers", "Off") }
        val kept = AppPrefs(sp).apply { dropRetired() }
        assertEquals(SwipeSlot.Empty, SwipeSlots.parse(kept.internal.keySwipes.getValue()).resolve("full/a", true))
        assertTrue(kept.keyboard.swipeUp.getValue())
        assertFalse(sp.contains("swipe_layers"))
    }

    // both in one place (an import): the symbol moved where it reads, at the next start
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
        // 0.6's own two in one place: the up one moved off the down one's
        sp.edit { clear(); putString("key_swipes", ""); putString("swipe_up_position", "TopLeft"); putString("swipe_down_position", "TopLeft") }
        AppPrefs(sp).apply { dropRetired() }.let { assertEquals(KeyMarkPosition.BottomLeft, it.keyboard.swipeUpPosition.getValue()) }
        // apart already: as stored
        sp.edit { clear(); putString("punctuation_position", "BottomLeft"); putString("shortcut_mark_position", "TopLeft") }
        AppPrefs(sp).apply { dropRetired() }.let { assertEquals(KeyMarkPosition.BottomLeft, it.keyboard.swipeUpPosition.getValue()) }
    }

    // the old drawing settings and voice_polish, of either type: dropped, no crash
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

    // 按键音音量 is a 0..100 slider (0 shown 系统默认, off with the sound); a 0.5 level is kept
    @Test
    @Uat("ST-111")
    fun theKeySoundVolumeIsASliderThatKeepsAnOlderLevel() {
        val context = RuntimeEnvironment.getApplication()
        sp.edit { putInt("button_sound_volume", 50); putString("button_sound_volume_level", "3") }
        val prefs = AppPrefs(sp)
        assertEquals("the old level kept", 50, prefs.keyboard.soundOnKeyPressVolume.getValue())
        val uis = prefs.keyboard.managedPreferencesUi
        assertTrue(uis.filterIsInstance<ManagedPreferenceUi.Levels>().none { level -> level.keys.any { it.first == "button_sound_volume" } })
        assertTrue(uis.none { it.key == "button_sound_volume_level" })
        val slider = uis.filterIsInstance<ManagedPreferenceUi.SeekBarInt>().single { it.key == "button_sound_volume" }
        assertEquals(0 to 100, slider.min to slider.max)
        assertTrue("a sound plays as it is let go", slider.preview != null)
        val ui = slider.createUi(context)
        assertEquals(context.getString(org.fcitx.fcitx5.android.R.string.system_default), ui.defaultLabel)
        assertEquals(0, ui.default)
        prefs.keyboard.soundOnKeyPress.setValue(InputFeedbacks.InputFeedbackMode.Disabled)
        assertFalse("off with the sound", slider.isEnabled())
        prefs.keyboard.soundOnKeyPress.setValue(InputFeedbacks.InputFeedbackMode.Enabled)
        assertTrue(slider.isEnabled())
    }

    // they rule the favourites too, which stay usable with the history off; the rest is about new copies
    @Test
    @Uat("TB-75")
    fun withTheHistoryOffWhatAlsoRulesTheFavouritesStaysSettable() {
        val prefs = AppPrefs(sp)
        prefs.clipboard.clipboardListening.setValue(false)
        val enabled = prefs.clipboard.managedPreferencesUi.associate { it.key to it.isEnabled() }
        assertNamedRows(
            "粘贴后回到键盘" to { assertEquals(true, enabled["clipboard_return_after_paste"]) },
            "隐藏敏感信息" to { assertEquals(true, enabled["clipboard_mask_sensitive"]) },
            "the limit: copies alone" to { assertEquals(false, enabled["clipboard_limit"]) },
        )
    }

    // the column's lists before the first unlock: what a direct boot's AppPrefs reads, copied at a sync and at every change
    @Test
    @Config(application = FcitxApplication::class)
    fun theScrollListsAreInTheDeviceProtectedCopy() {
        val de = PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication().createDeviceProtectedStorageContext())
        val set = ScrollListStore.Empty.with(ScrollDefaults.NUMBER, listOf(ScrollItem.Text("#"))).serialize()
        de.edit { remove(AppPrefs.SCROLL_LISTS) }
        sp.edit { putString(AppPrefs.SCROLL_LISTS, set) }
        AppPrefs(sp).syncToDeviceEncryptedStorage()
        fun locked() = ScrollListStore.parse(AppPrefs(de).internal.scrollLists.getValue())[ScrollDefaults.NUMBER]
        assertEquals(listOf(ScrollItem.Text("#")), locked())
        // the keyboard's menu, no page stopping after it to sync
        val pref = AppPrefs.getInstance().internal.scrollLists
        try {
            pref.setValue(ScrollListStore.parse(set).with(ScrollDefaults.NUMBER, listOf(ScrollItem.Text("@"))).serialize())
            assertEquals(listOf(ScrollItem.Text("@")), locked())
        } finally {
            pref.setValue(ScrollListStore.HEADER)
        }
    }
}
