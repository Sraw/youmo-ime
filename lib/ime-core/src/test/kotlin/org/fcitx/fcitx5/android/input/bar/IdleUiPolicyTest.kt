/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

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
    )

    private fun decide(change: Inputs.() -> Inputs) = IdleUiPolicy.decide(quiet.change())

    @Test
    fun withNothingGoingOnTheToolbarFollowsItsDefault() {
        assertEquals(Display.Empty, IdleUiPolicy.decide(quiet))
        assertEquals(Display.Toolbar, decide { copy(expandToolbarByDefault = true) })
    }

    @Test
    fun togglingTheToolbarFlipsWhicheverDefaultIsSet() {
        assertEquals(Display.Toolbar, decide { copy(isToolbarManuallyToggled = true) })
        assertEquals(
            Display.Empty,
            decide { copy(expandToolbarByDefault = true, isToolbarManuallyToggled = true) }
        )
    }

    @Test
    fun aForcedNumberRowBeatsEverythingElse() {
        val busy = quiet.copy(
            isClipboardFresh = true, isInlineSuggestionPresent = true,
            isPasswordField = true, numberRowMode = NumberRowMode.ForceShow,
        )
        assertEquals(Display.NumberRow, IdleUiPolicy.decide(busy))
    }

    @Test
    fun aFreshClipboardBeatsAnInlineSuggestion() {
        assertEquals(
            Display.Clipboard,
            decide { copy(isClipboardFresh = true, isInlineSuggestionPresent = true) }
        )
    }

    @Test
    fun anInlineSuggestionBeatsThePasswordNumberRow() {
        assertEquals(
            Display.InlineSuggestion,
            decide { copy(isInlineSuggestionPresent = true, isPasswordField = true) }
        )
    }

    @Test
    fun aPasswordFieldGetsTheNumberRow() {
        assertEquals(Display.NumberRow, decide { copy(isPasswordField = true) })
    }

    @Test
    fun theNumberRowIsSkippedWhenTheLayoutIsNumericAlready() {
        assertEquals(Display.Empty, decide { copy(isPasswordField = true, isNumberLayout = true) })
    }

    @Test
    fun theNumberRowIsSkippedWhenTheUserHidIt() {
        assertEquals(
            Display.Empty,
            decide { copy(isPasswordField = true, numberRowMode = NumberRowMode.ForceHide) }
        )
    }

    @Test
    fun hidingTheNumberRowDoesNotHideAClipboardSuggestion() {
        assertEquals(
            Display.Clipboard,
            decide { copy(numberRowMode = NumberRowMode.ForceHide, isClipboardFresh = true) }
        )
    }

    @Test
    fun aForcedNumberRowShowsEvenOnANumericLayout() {
        assertEquals(
            Display.NumberRow,
            decide { copy(numberRowMode = NumberRowMode.ForceShow, isNumberLayout = true) }
        )
    }

    @Test
    fun aPasswordFieldOnANumericLayoutFallsThroughToTheToolbarRule() {
        assertEquals(
            Display.Toolbar,
            decide { copy(isPasswordField = true, isNumberLayout = true, expandToolbarByDefault = true) }
        )
    }
}
