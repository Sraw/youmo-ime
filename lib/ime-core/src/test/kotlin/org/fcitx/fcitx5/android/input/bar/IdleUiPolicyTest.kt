/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.bar.IdleUiPolicy.Display
import org.fcitx.fcitx5.android.input.bar.IdleUiPolicy.Inputs
import org.fcitx.fcitx5.android.input.bar.IdleUiPolicy.NumberRowMode
import org.junit.Assert.assertEquals
import org.junit.Test

class IdleUiPolicyTest {

    private val quiet = Inputs(
        numberRowMode = NumberRowMode.Auto,
        isClipboardFresh = false,
        isInlineSuggestionPresent = false,
        isPasswordField = false,
        isNumberLayout = false,
        expandToolbarByDefault = false,
        isToolbarManuallyToggled = false,
        isCalculationPresent = false,
    )

    private fun decide(change: Inputs.() -> Inputs) = IdleUiPolicy.decide(quiet.change())

    @Test
    fun whatTheIdleBarShows() = assertRows(
        "with nothing going on the toolbar follows its default (off)" to { assertEquals(Display.Empty, IdleUiPolicy.decide(quiet)) },
        "with nothing going on the toolbar follows its default (on)" to { assertEquals(Display.Toolbar, decide { copy(expandToolbarByDefault = true) }) },
        "toggling the toolbar flips the default (off)" to { assertEquals(Display.Toolbar, decide { copy(isToolbarManuallyToggled = true) }) },
        "toggling the toolbar flips the default (on)" to { assertEquals(Display.Empty, decide { copy(expandToolbarByDefault = true, isToolbarManuallyToggled = true) }) },
        "a forced number row beats everything else" to {
            assertEquals(Display.NumberRow, decide { copy(isClipboardFresh = true, isInlineSuggestionPresent = true, isPasswordField = true, numberRowMode = NumberRowMode.ForceShow) })
        },
        "a forced number row shows even on a numeric layout" to { assertEquals(Display.NumberRow, decide { copy(numberRowMode = NumberRowMode.ForceShow, isNumberLayout = true) }) },
        "a number row asked for comes before a sum's result" to { assertEquals(Display.NumberRow, decide { copy(isCalculationPresent = true, numberRowMode = NumberRowMode.ForceShow) }) },
        "a sum's result comes before the clipboard" to { assertEquals(Display.Calculation, decide { copy(isCalculationPresent = true, isClipboardFresh = true) }) },
        "a sum's result comes before the password number row" to { assertEquals(Display.Calculation, decide { copy(isCalculationPresent = true, isPasswordField = true) }) },
        "a fresh clipboard beats an inline suggestion" to { assertEquals(Display.Clipboard, decide { copy(isClipboardFresh = true, isInlineSuggestionPresent = true) }) },
        "hiding the number row does not hide a clipboard suggestion" to { assertEquals(Display.Clipboard, decide { copy(numberRowMode = NumberRowMode.ForceHide, isClipboardFresh = true) }) },
        "an inline suggestion beats the password number row" to { assertEquals(Display.InlineSuggestion, decide { copy(isInlineSuggestionPresent = true, isPasswordField = true) }) },
        "a password field gets the number row" to { assertEquals(Display.NumberRow, decide { copy(isPasswordField = true) }) },
        "the number row is skipped when the layout is numeric already" to { assertEquals(Display.Empty, decide { copy(isPasswordField = true, isNumberLayout = true) }) },
        "the number row is skipped when the user hid it" to { assertEquals(Display.Empty, decide { copy(isPasswordField = true, numberRowMode = NumberRowMode.ForceHide) }) },
        "a password field on a numeric layout falls through to the toolbar rule" to {
            assertEquals(Display.Toolbar, decide { copy(isPasswordField = true, isNumberLayout = true, expandToolbarByDefault = true) })
        },
    )
}
