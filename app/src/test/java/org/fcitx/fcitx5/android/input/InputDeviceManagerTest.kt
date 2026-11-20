/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import io.mockk.every
import io.mockk.mockk
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class InputDeviceManagerTest {

    // a keyboard attached, as the system has it: no touch keyboard
    private val service = mockk<FcitxInputMethodService> {
        every { superEvaluateInputViewShown() } returns false
    }
    private val field = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
    private val key = mockk<KeyEvent>(relaxed = true) {
        every { unicodeChar } returns 'a'.code
        every { deviceId } returns 5
    }

    /** Till a hardware key is typed, not the next tap. */
    @Test
    fun inTheSystemsModeTheButtonsTouchKeyboardStaysTillAHardwareKey() {
        val mode = AppPrefs.getInstance().candidates.mode
        try {
            mode.setValue(FloatingCandidatesMode.SystemDefault)
            val mgr = InputDeviceManager {}
            assertFalse(mgr.evaluateOnStartInputView(field, service))
            mgr.showTouchKeyboard()
            assertTrue(mgr.isVirtualKeyboard)
            mgr.evaluateOnViewClicked(service)
            assertTrue(mgr.isVirtualKeyboard)
            assertTrue(mgr.evaluateOnStartInputView(field, service))
            mgr.evaluateOnKeyDown(key, service)
            assertFalse(mgr.isVirtualKeyboard)
            mgr.evaluateOnViewClicked(service)
            assertFalse(mgr.isVirtualKeyboard)
        } finally {
            mode.setValue(FloatingCandidatesMode.InputDevice)
        }
    }
}
