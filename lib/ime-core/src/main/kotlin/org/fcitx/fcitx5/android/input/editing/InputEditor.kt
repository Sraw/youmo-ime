/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.input.cursor.CursorRange

/** The slice of `InputConnection` editing needs, so the logic tests against a `StringBuilder`; positions follow `InputConnection` exactly. */
interface InputEditor {

    /** No connection between sessions; calls that update the cursor prediction check this first so none goes stale. */
    val isAvailable: Boolean

    /** False when the editor did not take [text] (no connection, or too large for the binder: [CommitPieces]); nothing went in. */
    fun commitText(text: String, newCursorPosition: Int): Boolean

    /** Replaces the composing region (or else the selection); [text] stays composing. */
    fun setComposingText(text: CharSequence, newCursorPosition: Int)

    fun setSelection(start: Int, end: Int)

    fun finishComposingText()

    fun setComposingRegion(start: Int, end: Int)

    /** Before the selection start; null when the editor will not say; may be shorter than asked. */
    fun textBeforeCursor(length: Int): CharSequence?

    /** After the selection end; null when the editor will not say. */
    fun textAfterCursor(length: Int): CharSequence?

    /** Around the selection widened to the composing region; [inCodePoints] so one press removes a whole emoji. */
    fun deleteSurroundingText(before: Int, after: Int, inCodePoints: Boolean)

    /** One atomic change for the editor. */
    fun batchEdit(block: () -> Unit)

    /** Read at once (Android 13's `takeSnapshot`), not the last report; -1 ends for none; null when it cannot tell. */
    fun composingRegion(): CursorRange? = null
}
