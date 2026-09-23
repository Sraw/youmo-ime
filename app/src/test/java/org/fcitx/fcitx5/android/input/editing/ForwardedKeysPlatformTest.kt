/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import android.view.KeyCharacterMap
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** `:lib:ime-core` cannot see the framework, so its copies of these constants are held to it here. */
class ForwardedKeysPlatformTest {

    @Test
    fun theConstantsAreThePlatformsOwn() {
        assertEquals(KeyEvent.KEYCODE_UNKNOWN, ForwardedKeys.KEYCODE_UNKNOWN)
        assertEquals(KeyCharacterMap.PICKER_DIALOG_INPUT.code, ForwardedKeys.PICKER_DIALOG_INPUT)
    }
}
