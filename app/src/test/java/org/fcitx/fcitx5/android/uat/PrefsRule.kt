/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkPosition
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle
import org.fcitx.fcitx5.android.input.keyboard.OneHanded
import org.fcitx.fcitx5.android.input.keyboard.SwipeLayersChoice
import org.fcitx.fcitx5.android.input.keys.First
import org.fcitx.fcitx5.android.input.keys.ItemMark
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.keys.UserList
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.input.shortcut.SwipeMigration
import org.fcitx.fcitx5.android.input.shortcut.SwipeOrientation
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.ui.main.settings.PopupOverridesFragment
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.rules.ExternalResource

/** Set back last set first: the prefs outlive the test (Robolectric keeps the sandbox). Runs after the test's `@After`. */
class PrefsRule : ExternalResource() {

    private val restore = mutableListOf<() -> Unit>()

    private val store get() = PreferenceManager.getDefaultSharedPreferences(appContext)
    private var found: Map<String, Any?> = emptyMap()

    // 表情 opens the panel last shown, which another test may have left at 颜文字 or the symbols
    override fun before() {
        found = HashMap(store.all)
        set(AppPrefs.getInstance().internal.lastPickerType, PickerWindow.Key.Emoji.name)
        set(AppPrefs.getInstance().internal.symbolPanelCategory, "")
        // the floating keyboard's spot and width, which a drag's end writes: another test's drag moved the theme maker's
        // floating shots
        AppPrefs.getInstance().internal.run {
            listOf(
                floatingKeyboardX, floatingKeyboardY, floatingPortraitX, floatingPortraitY, floatingLandscapeX, floatingLandscapeY,
                floatingPortraitWidthDp, floatingLandscapeWidthDp,
            ).forEach { set(it, it.defaultValue) }
        }
    }

    fun <T : Any> set(pref: ManagedPreference<T>, value: T) {
        val was = pref.getValue()
        restore += { pref.setValue(was) }
        pref.setValue(value)
    }

    /** A key's long press set as 按键字符和快捷键 sets it: by its name (`full/q`, KeyId.of), its [first] the default's unless given. */
    fun setList(name: String, items: List<String> = emptyList(), first: String? = null, off: Boolean = false, marks: Map<String, ItemMark> = emptyMap()) {
        val pref = AppPrefs.getInstance().internal.keyLists
        val list = if (off) UserList.Off else UserList(off = false, items = items, first = first?.let(First::Literal) ?: First.Default, marks = marks)
        // a list turned off is kept where no default is told here
        set(pref, KeyLists.of((KeyLists.parse(pref.getValue())?.lists.orEmpty()) + (KeyId.of(name) to list)).serialize())
    }

    /** 0.5's shortcuts as 0.6's slots: a character key's on the way that is not its long press's first. */
    fun setShortcuts(shortcuts: KeyShortcuts) {
        val now = AppPrefs.getInstance().internal.keySwipes.getValue().let { if (it.isEmpty()) SwipeSlots.Default else SwipeSlots.parse(it) }
        val up = now.orientation == SwipeOrientation.FirstDown
        val made = SwipeMigration.withShortcuts(now, shortcuts, up)
        set(AppPrefs.getInstance().internal.keySwipes, made.serialize())
    }

    /** As SwipeMigration migrates it; the slots start again from the preset's: a [setShortcuts] before it is lost. */
    fun setLayers(choice: SwipeLayersChoice) {
        val k = AppPrefs.getInstance().keyboard
        val r = SwipeMigration.of(choice.layers, upgraded = true, KeyShortcuts.Empty, k.swipeUpPosition.getValue().at, k.swipeDownPosition.getValue().at)
        set(AppPrefs.getInstance().internal.keySwipes, SwipeSlots.Default.applied(r.slots.orientation).serialize())
        setWays(r.up, r.down)
        set(k.swipeUpPosition, KeyMarkPosition.of(r.upAt))
        set(k.swipeDownPosition, KeyMarkPosition.of(r.downAt))
    }

    fun setWays(up: Boolean, down: Boolean) {
        set(AppPrefs.getInstance().keyboard.swipeUp, up)
        set(AppPrefs.getInstance().keyboard.swipeDown, down)
    }

    fun setStyle(style: KeyboardStyle, landscape: Boolean? = null, side: OneHanded? = null) {
        side?.let { set(ThemeManager.prefs.oneHandedSide, it) }
        TestStyles.set(style, landscape)
    }

    /** Each shape's default by its width (竖屏 普通; 横屏 split from 600 dp). */
    fun setStyleDefault() = TestStyles.clear()

    /** Of no setting this version declares (0.5's long presses). */
    fun setStored(key: String, value: String) = store.edit().putString(key, value).commit()

    fun later(undo: () -> Unit) {
        restore += undo
    }

    override fun after() {
        restore.asReversed().forEach { it() }
        restore.clear()
        putBackFound()
        // the key page's one step back outlives the page
        PopupOverridesFragment.forgetUndo()
    }

    // in one commit: a setting a test forgot to put back does not leak into the next test
    private fun putBackFound() {
        val now = store.all
        val e = store.edit()
        var changed = false
        (now.keys - found.keys).forEach { e.remove(it); changed = true }
        found.forEach { (k, v) ->
            if (now[k] == v) return@forEach
            changed = true
            @Suppress("UNCHECKED_CAST")
            when (v) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is String -> e.putString(k, v)
                is Set<*> -> e.putStringSet(k, v as Set<String>)
                else -> e.remove(k)
            }
        }
        if (changed) e.commit()
    }
}
