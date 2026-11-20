/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException

/** Ink on the keys of an input method whose session cannot be made: that input method latches, not handwriting's recognizer. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class EngineBridgeInkOnKeysTest {

    private val bridge = EngineBridge()

    @Test
    fun inkOnTheKeysOfAnInputMethodWhoseSessionFailsAsItIsMadeIsThatInputMethodsFailure() {
        bridge.unloadable.clear()
        bridge.makeOnKeys = { throw FileNotFoundException("its table") }
        var sent = 0
        try {
            assertFalse(bridge.sendInk(Action.Ink(1), InputMethodCatalog.PINYIN) { sent++ })
            assertEquals(0, sent)
            assertTrue(InputMethodCatalog.PINYIN in bridge.unloadable)
            assertFalse("handwriting" in bridge.unloadable)
        } finally {
            bridge.unloadable.clear()
        }
    }

    // handwriting's session given up turns the toolbar's 手写 off rather than drawing ink that reads nothing
    @Test
    fun handwritingsSessionGivenUpTurnsTheSwitchOff() {
        val open = AppPrefs.getInstance().internal.handwritingOpen
        bridge.unloadable.clear()
        open.setValue(true)
        try {
            assertFalse(bridge.handwriting { throw FileNotFoundException("its data") })
            assertFalse(open.getValue())
            assertTrue("handwriting" in bridge.unloadable)
        } finally {
            bridge.unloadable.clear()
            open.setValue(false)
        }
    }
}
