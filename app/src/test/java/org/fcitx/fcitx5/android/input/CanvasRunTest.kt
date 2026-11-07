/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `-PcanvasKeyboard`'s switch: on for each harness, left to the next test as it was, a test's own choice kept. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class CanvasRunTest {

    private val switch get() = AppPrefs.getInstance().internal.canvasKeyboard

    private var wasOn = false

    @Before
    fun run() {
        wasOn = CanvasRun.on
        CanvasRun.on = true
        CanvasRun.over()
        switch.setValue(false)
    }

    @After
    fun back() {
        CanvasRun.over()
        CanvasRun.on = wasOn
    }

    @Test
    fun aHarnessTurnsItOnAndItsCloseTurnsItBack() {
        CanvasRun.begin()
        assertTrue(switch.getValue())
        // a second harness in the same test keeps what it was first
        CanvasRun.begin()
        CanvasRun.end()
        assertFalse("as before the run turned it on", switch.getValue())
    }

    @Test
    fun theTestsEndTurnsItBackWithNoClose() {
        CanvasRun.begin()
        CanvasRun.over()
        assertFalse(switch.getValue())
    }

    @Test
    fun aTestsOwnChoiceIsKeptTillItEnds() {
        CanvasRun.begin()
        CanvasRun.chose()
        assertFalse("put back first", switch.getValue())
        switch.setValue(false)
        CanvasRun.begin()
        assertEquals("the test's own, not turned on", false, switch.getValue())
        CanvasRun.over()
        CanvasRun.begin()
        assertTrue("the next test's run on again", switch.getValue())
        CanvasRun.end()
        assertEquals(false, switch.getValue())
    }

    @Test
    fun notThisRunNothingTurnedOn() {
        CanvasRun.on = false
        CanvasRun.begin()
        assertFalse(switch.getValue())
    }
}
