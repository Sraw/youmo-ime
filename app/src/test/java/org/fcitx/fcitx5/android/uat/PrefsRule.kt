/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.junit.rules.ExternalResource

/**
 * What a test sets is set back to what it was, last set first: the prefs outlive the test
 * (Robolectric keeps the sandbox). Runs after the test's own `@After`, its keyboard closed first.
 */
class PrefsRule : ExternalResource() {

    private val restore = mutableListOf<() -> Unit>()

    // 表情 opens the panel last shown, which app code writes and another test may have left at 颜文字 or the
    // symbols: the emoji at each test's start (R20 F6608)
    override fun before() {
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
    }
}
