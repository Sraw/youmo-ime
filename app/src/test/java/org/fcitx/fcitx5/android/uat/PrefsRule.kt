/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.rules.ExternalResource

/**
 * What a test sets is set back to what it was, last set first: the prefs outlive the test
 * (Robolectric keeps the sandbox). Runs after the test's own `@After`, its keyboard closed first.
 */
class PrefsRule : ExternalResource() {

    private val restore = mutableListOf<() -> Unit>()

    // the app's settings store (AppPrefs and ThemeManager.prefs alike) as the test found it
    private val store get() = PreferenceManager.getDefaultSharedPreferences(appContext)
    private var found: Map<String, Any?> = emptyMap()

    // 表情 opens the panel last shown, which app code writes and another test may have left at 颜文字 or the
    // symbols: the emoji at each test's start (R20 F6608)
    override fun before() {
        found = HashMap(store.all)
        set(AppPrefs.getInstance().internal.lastPickerType, PickerWindow.Key.Emoji.name)
        // the floating keyboard's spot, which a drag's end writes: another test's drag moved the theme maker's
        // floating shots (ThemeMakerLayoutTest)
        AppPrefs.getInstance().internal.let { set(it.floatingKeyboardX, it.floatingKeyboardX.defaultValue); set(it.floatingKeyboardY, it.floatingKeyboardY.defaultValue) }
    }

    fun <T : Any> set(pref: ManagedPreference<T>, value: T) {
        val was = pref.getValue()
        restore += { pref.setValue(was) }
        pref.setValue(value)
    }

    /** Something else a test changed that outlives it, put back with the prefs. */
    fun later(undo: () -> Unit) {
        restore += undo
    }

    override fun after() {
        restore.asReversed().forEach { it() }
        restore.clear()
        putBackFound()
    }

    // every key the test (or app code it ran) changed or added set back to what it was, in one commit: a setting
    // a test forgot to put back does not leak into the next test
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
