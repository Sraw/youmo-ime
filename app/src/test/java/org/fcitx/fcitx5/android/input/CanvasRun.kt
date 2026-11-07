/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.data.prefs.AppPrefs

/**
 * `-PcanvasKeyboard`'s run (specs/testing.md): every keyboard a test starts drawn by the canvas,
 * unless the test chose the drawing itself (the canvas switch set through `PrefsRule`). The switch
 * turned on here is put back as it was when the test's harness closes and when its `PrefsRule` ends,
 * whichever comes first: nothing of it is left to the next test.
 */
object CanvasRun {
    /** Whether this is `-PcanvasKeyboard`'s run (set by `CanvasRunTest` alone, put back after). */
    var on = System.getProperty("youmo.canvasKeyboard") == "true"

    // the test running set the drawing itself (PrefsRule.set): this run leaves it be
    private var chosen = false

    // what the switch was before this run turned it on; null, not turned on
    private var was: Boolean? = null

    private val switch get() = AppPrefs.getInstance().internal.canvasKeyboard

    /** A keyboard about to be started ([KeyboardHarness]): the canvas turned on, in this run, unless the test chose. */
    fun begin() {
        if (!on || chosen || was != null) return
        was = switch.getValue()
        switch.setValue(true)
    }

    /** The test sets the switch itself: put back what this run turned on, and leave it be until the test ends. */
    fun chose() {
        end()
        chosen = true
    }

    /** The switch put back as it was before [begin] (a harness closed). */
    fun end() {
        was?.let(switch::setValue)
        was = null
    }

    /** The test is over (PrefsRule's end): put back, and the next test's choice its own. */
    fun over() {
        end()
        chosen = false
    }
}
