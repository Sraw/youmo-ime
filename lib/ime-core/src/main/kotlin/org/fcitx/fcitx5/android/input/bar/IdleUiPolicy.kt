/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

/** What the bar shows with no input in progress; [decide] is the priority order. */
object IdleUiPolicy {

    enum class Display { Empty, Toolbar, Clipboard, NumberRow, InlineSuggestion, Calculation }

    enum class NumberRowMode { Auto, ForceShow, ForceHide }

    data class Inputs(
        val numberRowMode: NumberRowMode,
        val isClipboardFresh: Boolean,
        val isInlineSuggestionPresent: Boolean,
        val isPasswordField: Boolean,
        val isNumberLayout: Boolean,
        /** Opened by the user this session. */
        val isToolbarManuallyToggled: Boolean,
        /** Offered till the cursor moves. */
        val isCalculationPresent: Boolean,
    )

    /** A pinned number row, a sum's result, a fresh clip, an inline suggestion, a password field's number row (unless numeric or hidden), the user's toolbar, nothing. */
    fun decide(inputs: Inputs): Display = when {
        inputs.numberRowMode == NumberRowMode.ForceShow -> Display.NumberRow
        inputs.isCalculationPresent -> Display.Calculation
        inputs.isClipboardFresh -> Display.Clipboard
        inputs.isInlineSuggestionPresent -> Display.InlineSuggestion
        inputs.isPasswordField && !inputs.isNumberLayout && inputs.numberRowMode != NumberRowMode.ForceHide ->
            Display.NumberRow
        !inputs.isToolbarManuallyToggled -> Display.Empty
        else -> Display.Toolbar
    }
}
