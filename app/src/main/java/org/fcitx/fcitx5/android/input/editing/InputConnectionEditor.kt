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

    override fun deleteSurroundingText(before: Int, after: Int, inCodePoints: Boolean) {
        val ic = connection() ?: return
        if (inCodePoints && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ic.deleteSurroundingTextInCodePoints(before, after)
        } else {
            ic.deleteSurroundingText(before, after)
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
