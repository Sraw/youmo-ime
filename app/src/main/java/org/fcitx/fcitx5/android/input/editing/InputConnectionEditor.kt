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

    override fun deleteSurroundingText(
        before: Int,
        after: Int,
        inCodePoints: Boolean,
        composingBefore: Int,
        composingAfter: Int,
    ) {
        val ic = connection() ?: return
        when {
            !inCodePoints -> ic.deleteSurroundingText(before, after)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ->
                ic.deleteSurroundingTextInCodePoints(before, after)
            // No code-point variant before API 24: measure the code points and delete that
            // many units, so a surrogate pair is never split. The editor deletes from the edges
            // of the composing region where that sticks out past the selection, so measure from
            // there. Unmeasurable (no text at all) counts as one unit per code point, which is
            // what the plain variant would have done anyway. A shorter answer than asked for is
            // taken as the end of the text: the request is a few units, far below any limit an
            // editor puts on what it reveals. (EditingSession, which knows the cursor, predicts
            // `count` units for a read that stops short of the text's start; an editor revealing
            // less than a few units would make the two disagree until the next cursor report.)
            // Malformed text is refused outright, as the code-point variant refuses it.
            else -> {
                val unitsBefore = measure(before, composingBefore, fromEnd = true) {
                    ic.getTextBeforeCursor(it, 0)
                } ?: return
                val unitsAfter = measure(after, composingAfter, fromEnd = false) {
                    ic.getTextAfterCursor(it, 0)
                } ?: return
                ic.deleteSurroundingText(unitsBefore, unitsAfter)
            }
        }
    }

    /**
     * Units taken by [count] code points beyond [skip] units of composing text, reading
     * outwards from the cursor; null for malformed text.
     */
    private inline fun measure(
        count: Int,
        skip: Int,
        fromEnd: Boolean,
        fetch: (Int) -> CharSequence?,
    ): Int? {
        if (count <= 0) return 0
        val n = count.coerceAtMost(MAX_MEASURED)
        val text = fetch(n * 2 + skip)?.takeIf { it.length > skip } ?: return count
        return if (fromEnd) {
            CodePoints.lengthOfLast(text.subSequence(0, text.length - skip), n)
        } else {
            CodePoints.lengthOfFirst(text.subSequence(skip, text.length), n)
        }
    }

    private companion object {
        /** Keeps `2 * count` from overflowing; far more text than any deletion touches. */
        const val MAX_MEASURED = 1 shl 20
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
