/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import android.os.Build
import androidx.annotation.RequiresApi
import android.view.inputmethod.InputConnection
import org.fcitx.fcitx5.android.input.cursor.CursorRange

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

    // false also when the one-way binder call failed: the framework catches the RemoteException
    override fun commitText(text: String, newCursorPosition: Int): Boolean =
        connection()?.commitText(text, newCursorPosition) ?: false

    override fun setComposingText(text: CharSequence, newCursorPosition: Int) {
        connection()?.setComposingText(text, newCursorPosition)
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

    override fun textAfterCursor(length: Int): CharSequence? =
        connection()?.getTextAfterCursor(length, 0)

    override fun deleteSurroundingText(before: Int, after: Int, inCodePoints: Boolean) {
        val ic = connection() ?: return
        if (inCodePoints) {
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

    // takeSnapshot is Android 13's: not there to call before (a NoSuchMethodError on Android 10)
    override fun composingRegion(): CursorRange? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Snapshots.composingRegion(connection()) else null

    // TextSnapshot named in a class of its own: one an older verifier resolves while checking this class
    // (Android 10 has no TextSnapshot) would fail it at load, before any version check runs
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object Snapshots {
        fun composingRegion(ic: InputConnection?): CursorRange? =
            ic?.takeSnapshot()?.let { CursorRange(it.compositionStart, it.compositionEnd) }
    }
}
