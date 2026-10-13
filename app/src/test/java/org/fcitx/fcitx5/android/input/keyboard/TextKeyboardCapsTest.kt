/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import androidx.core.view.allViews
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.main.settings.PlaceholderStrings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Shift on by itself at a sentence start ([TextKeyboard.applyAutoCaps]), as [AutoCaps] decides: one
 * letter capitalised, then lower case again; off again where no sentence starts; Shift or caps lock
 * the user turned on left as they are. Each state goes to AutoCaps and back as itself, whatever
 * order either enum declares them in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class TextKeyboardCapsTest {

    private val typed = ArrayList<String>()

    private val keyboard = TextKeyboard(PlaceholderStrings(RuntimeEnvironment.getApplication()), ThemePreset.PixelDark).apply {
        keyActionListener = KeyActionListener { action, _ -> if (action is KeyAction.FcitxKeyAction) typed += action.act }
    }

    private fun type(letter: String): String {
        keyboard.allViews.filterIsInstance<TextKeyView>().first { (it.def as? KeyDef.Appearance.Text)?.displayText == letter }.performClick()
        return typed.last()
    }

    @Test
    fun aSentenceStartCapitalisesOneLetter() {
        keyboard.applyAutoCaps(true)
        assertEquals("Q", type("Q"))
        assertEquals("q", type("Q"))
        // a letter deleted back: no sentence starts there
        keyboard.applyAutoCaps(true)
        keyboard.applyAutoCaps(false)
        assertEquals("q", type("Q"))
    }

    @Test
    fun theUsersShiftAndCapsLockStayWhereNoSentenceStarts() {
        keyboard.caps.performClick()
        keyboard.applyAutoCaps(false)
        assertEquals("Q", type("Q"))
        keyboard.caps.performLongClick()
        keyboard.applyAutoCaps(true)
        keyboard.applyAutoCaps(false)
        assertEquals("Q", type("Q"))
        assertEquals("Q", type("Q"))
    }
}
