/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

/**
 * What the candidate bar shows while there is no input in progress ("idle"): a number row,
 * a clipboard suggestion, an inline (autofill) suggestion, the toolbar, or nothing.
 * Several of these can be wanted at once; [decide] is the priority order between them.
 */
object IdleUiPolicy {

    enum class Display { Empty, Toolbar, Clipboard, NumberRow, InlineSuggestion }

    /** The number row can be pinned either way by the user; otherwise it follows the editor. */
    enum class NumberRowMode { Auto, ForceShow, ForceHide }

    data class Inputs(
        val numberRowMode: NumberRowMode,
        val isClipboardFresh: Boolean,
        val isInlineSuggestionPresent: Boolean,
        /** the editor is a password field */
        val isPasswordField: Boolean,
        /** the keyboard is already showing its own number layout */
        val isNumberLayout: Boolean,
        /** the "expand toolbar by default" preference */
        val expandToolbarByDefault: Boolean,
        /** the user has flipped the toolbar away from its default this session */
        val isToolbarManuallyToggled: Boolean,
    )

    /**
     * In priority order: a number row the user asked for, a fresh clipboard entry, an inline
     * suggestion, the number row for a password field (unless the layout is numeric already, or
     * the user hid it), then the toolbar or nothing.
     *
     * The last step is an XOR: the toolbar is on by default when [Inputs.expandToolbarByDefault]
     * and toggling flips it either way.
     */
    fun decide(i: Inputs): Display = when {
        i.numberRowMode == NumberRowMode.ForceShow -> Display.NumberRow
        i.isClipboardFresh -> Display.Clipboard
        i.isInlineSuggestionPresent -> Display.InlineSuggestion
        i.isPasswordField && !i.isNumberLayout && i.numberRowMode != NumberRowMode.ForceHide ->
            Display.NumberRow
        i.expandToolbarByDefault == i.isToolbarManuallyToggled -> Display.Empty
        else -> Display.Toolbar
    }
}
