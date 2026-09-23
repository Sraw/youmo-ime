/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

/**
 * The slice of `android.view.inputmethod.InputConnection` that editing logic actually needs.
 *
 * Having it as an interface is what lets the cursor and composing arithmetic be tested against
 * a plain `StringBuilder` instead of a live editor on a device.
 *
 * Positions and the meaning of `newCursorPosition` follow `InputConnection` exactly, so the
 * real implementation is a straight delegation.
 */
interface InputEditor {

    /**
     * Whether there is an editor to talk to right now. `InputMethodService` has no input
     * connection between sessions; operations that would otherwise update the IME's cursor
     * prediction check this first, so a dropped call does not leave a stale prediction behind.
     * (All of `EditingSession`'s do.)
     */
    val isAvailable: Boolean

    fun commitText(text: String, newCursorPosition: Int)

    fun setSelection(start: Int, end: Int)

    fun finishComposingText()

    fun setComposingRegion(start: Int, end: Int)

    /**
     * Up to [length] UTF-16 units immediately before the cursor (before the selection start,
     * when there is a selection), or null when the editor will not say. May be shorter than
     * asked near the start of the text, or when the editor chooses to reveal less.
     */
    fun textBeforeCursor(length: Int): CharSequence?

    /**
     * @param inCodePoints delete whole code points rather than UTF-16 units, so one press
     * removes a complete emoji instead of half a surrogate pair.
     */
    fun deleteSurroundingText(before: Int, after: Int, inCodePoints: Boolean)

    /** Groups the calls in [block] so the editor sees one atomic change. */
    fun batchEdit(block: () -> Unit)
}
