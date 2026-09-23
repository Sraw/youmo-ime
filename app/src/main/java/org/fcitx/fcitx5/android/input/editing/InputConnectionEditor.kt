/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import android.os.Build
import android.view.inputmethod.InputConnection

/**
 * Binds [InputEditor] to the real editor.
 *
 * The connection is fetched per call rather than held: `InputMethodService` hands out a new
 * one on every input session and the old one silently stops working. When there is none,
 * [isAvailable] is false and `EditingSession` skips the operations the service used to guard
 * with `currentInputConnection ?: return`; any call that still arrives is dropped here.
 */
class InputConnectionEditor(private val connection: () -> InputConnection?) : InputEditor {

    override val isAvailable: Boolean
        get() = connection() != null

    override fun commitText(text: String, newCursorPosition: Int) {
        connection()?.commitText(text, newCursorPosition)
    }

    override fun setSelection(start: Int, end: Int) {
        connection()?.setSelection(start, end)
    }

    override fun finishComposingText() {
        connection()?.finishComposingText()
    }

    override fun setComposingRegion(start: Int, end: Int) {
        connection()?.setComposingRegion(start, end)
    }

    override fun textBeforeCursor(length: Int): CharSequence? =
        connection()?.getTextBeforeCursor(length, 0)

    override fun deleteSurroundingText(before: Int, after: Int, inCodePoints: Boolean) {
        val ic = connection() ?: return
        when {
            !inCodePoints -> ic.deleteSurroundingText(before, after)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ->
                ic.deleteSurroundingTextInCodePoints(before, after)
            // No code-point variant before API 24: measure the code points and delete that
            // many units, so a surrogate pair is never split. Unmeasurable counts as one unit
            // per code point, which is what the plain variant would have done anyway. Fewer
            // than `n` units cannot hold `n` code points, so a shorter answer means the editor
            // held text back (some return "" rather than null) and is unmeasurable too.
            else -> ic.deleteSurroundingText(
                if (before == 0) 0 else ic.getTextBeforeCursor(before * 2, 0)
                    ?.takeIf { it.length >= before }
                    ?.let { CodePoints.lengthOfLast(it, before) } ?: before,
                if (after == 0) 0 else ic.getTextAfterCursor(after * 2, 0)
                    ?.takeIf { it.length >= after }
                    ?.let { CodePoints.lengthOfFirst(it, after) } ?: after,
            )
        }
    }

    override fun batchEdit(block: () -> Unit) {
        val ic = connection()
        if (ic == null) {
            block()
            return
        }
        ic.beginBatchEdit()
        try {
            block()
        } finally {
            ic.endBatchEdit()
        }
    }
}
