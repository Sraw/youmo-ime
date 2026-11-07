/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.CanvasRun
import org.junit.rules.ExternalResource

/**
 * What a test sets is set back to what it was, last set first: the prefs outlive the test
 * (Robolectric keeps the sandbox). Runs after the test's own `@After`, its keyboard closed first.
 */
class PrefsRule : ExternalResource() {

    private val restore = mutableListOf<() -> Unit>()

    fun <T : Any> set(pref: ManagedPreference<T>, value: T) {
        // the test chose the keys' drawing: -PcanvasKeyboard's run puts back what it turned on and leaves it be
        if (pref === AppPrefs.getInstance().internal.canvasKeyboard) CanvasRun.chose()
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
        CanvasRun.over()
    }
}
