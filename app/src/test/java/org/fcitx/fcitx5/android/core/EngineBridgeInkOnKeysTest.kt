/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.FcitxApplication
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

/**
 * Ink written on the keys of an input method whose own session cannot be made (a code table that does not read):
 * that input method's failure, latched as its own, no key sent; handwriting's pad not latched, so it still loads.
 */
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
            assertFalse(bridge.sendInk(Action.Ink(1), InputMethodCatalog.PINYIN, onKeys = true) { sent++ })
            assertEquals(0, sent)
            assertTrue(InputMethodCatalog.PINYIN in bridge.unloadable)
            assertFalse(InputMethodCatalog.HANDWRITING in bridge.unloadable)
        } finally {
            bridge.unloadable.clear()
        }
    }
}
