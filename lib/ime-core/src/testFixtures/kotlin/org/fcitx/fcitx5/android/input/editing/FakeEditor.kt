/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

/**
 * A text buffer that behaves like an editor, for testing [EditingSession].
 *
 * Models AOSP's `BaseInputConnection` over an `Editable`, which is what a plain `EditText` gives
 * an input method: a [StringBuilder], a selection (kept as given, so it may be reversed) and a
 * composing region. It records the calls so a test can assert on *how* the editor was driven,
 * not only on the resulting text.
 *
 * Faithfulness is not taken on trust: `InputEditorContractTest` in `:app` runs the same
 * scenarios against this and against the real `BaseInputConnection` under Robolectric, and
 * requires identical results.
 */
class FakeEditor(
    initial: String = "",
    cursor: Int = initial.length,
    /**
     * `commitText`/`setComposingText` place the cursor in the old text, clamped to it, and let
     * the edit carry it along (read from AOSP's `replaceTextInternal`). Newer versions (seen at
     * API 35, not at API 23; the exact version was not pinned down) then put it back for
     * `newCursorPosition == 0` at a collapsed cursor, so it ends up before the new text rather
     * than carried after it.
     */
    private val reselectsZeroAfterInsert: Boolean = true,
) : InputEditor {

    /** Set to false to model the gap between input sessions, when there is no connection. */
    override var isAvailable: Boolean = true

    /**
     * How much text before the cursor this editor is willing to reveal; null models an editor
     * that reveals none (`getTextBeforeCursor` returning null). Real editors cap it too.
     */
    var revealLimit: Int? = Int.MAX_VALUE

    private val buffer = StringBuilder(initial)

    /** Selection anchor and focus, as set; `start > end` is possible, as in `Selection`. */
    var selectionStart = cursor
        private set
    var selectionEnd = cursor
        private set

    /** Composing region, or `-1 to -1` when there is none. Always `start <= end`. */
    var composingStart = -1
        private set
    var composingEnd = -1
        private set

    val text: String get() = buffer.toString()

    /** Every call made on this editor, in order, for asserting on the interaction. */
    val calls = mutableListOf<String>()

    var batchDepth = 0
        private set

    /** The deepest nesting reached, so a test can prove a change was batched. */
    var maxBatchDepth = 0
        private set

    private val hasComposing get() = composingStart >= 0 && composingEnd >= 0

    private fun clearComposing() {
        composingStart = -1
        composingEnd = -1
    }

    /**
     * Where a cursor at [p] ends up after [from, to) is replaced by [length] characters. This is
     * how the selection spans of an `Editable` move: before the range they stay, after it they
     * shift, and inside it they land at the end of the new text.
     */
    private fun shift(p: Int, from: Int, to: Int, length: Int): Int = when {
        p < from -> p
        p > to -> p - (to - from) + length
        p == from && from != to -> p // a point at the very start of a replaced range stays put
        else -> from + length
    }

    /** `BaseInputConnection.replaceText`, shared by commitText and setComposingText. */
    private fun replaceText(text: String, newCursorPosition: Int, composing: Boolean) {
        var a: Int
        var b: Int
        if (hasComposing) {
            a = composingStart
            b = composingEnd
            clearComposing()
        } else {
            a = selectionStart.coerceAtLeast(0)
            b = selectionEnd.coerceAtLeast(0)
            if (b < a) a = b.also { b = a }
        }
        val c = (if (newCursorPosition > 0) newCursorPosition + b - 1 else newCursorPosition + a)
            .coerceIn(0, buffer.length)
        var cursor = shift(c, a, b, text.length)
        buffer.replace(a, b, text)
        if (reselectsZeroAfterInsert && newCursorPosition == 0 && a == b) cursor = c
        selectionStart = cursor
        selectionEnd = cursor
        if (composing && text.isNotEmpty()) {
            composingStart = a
            composingEnd = a + text.length
        }
    }

    override fun commitText(text: String, newCursorPosition: Int) {
        calls += "commitText($text, $newCursorPosition)"
        replaceText(text, newCursorPosition, composing = false)
    }

    /** Styling spans are not modelled: only the characters of [text] land in the buffer. */
    override fun setComposingText(text: CharSequence, newCursorPosition: Int) {
        calls += "setComposingText($text, $newCursorPosition)"
        replaceText(text.toString(), newCursorPosition, composing = true)
    }

    /** Test shorthand for the common `newCursorPosition = 1`. */
    fun setComposingText(text: String) = setComposingText(text, 1)

    /** Out-of-range positions are ignored, not clamped, as `BaseInputConnection` does. */
    override fun setSelection(start: Int, end: Int) {
        calls += "setSelection($start, $end)"
        val length = buffer.length
        if (start < 0 || end < 0 || start > length || end > length) return
        selectionStart = start
        selectionEnd = end
    }

    override fun finishComposingText() {
        calls += "finishComposingText()"
        clearComposing()
    }

    /** Clamped into the text and ordered; an empty region means no composing region. */
    override fun setComposingRegion(start: Int, end: Int) {
        calls += "setComposingRegion($start, $end)"
        val length = buffer.length
        val a = minOf(start, end).coerceIn(0, length)
        val b = maxOf(start, end).coerceIn(0, length)
        clearComposing()
        if (a != b) {
            composingStart = a
            composingEnd = b
        }
    }

    override fun textBeforeCursor(length: Int): CharSequence? {
        calls += "textBeforeCursor($length)"
        val limit = revealLimit ?: return null
        val a = minOf(selectionStart, selectionEnd)
        if (a <= 0) return ""
        val n = minOf(length, limit, a)
        return buffer.substring(a - n, a)
    }

    /**
     * Deletes around the selection -- widened to take in the composing region, so composing
     * text itself is never deleted -- and never the selection itself.
     *
     * The composing arguments are what the caller believes; this editor, like a real one,
     * works the widening out from its own composing region instead.
     */
    override fun deleteSurroundingText(
        before: Int,
        after: Int,
        inCodePoints: Boolean,
        composingBefore: Int,
        composingAfter: Int,
    ) {
        calls += "deleteSurroundingText($before, $after, inCodePoints=$inCodePoints)"
        var a = minOf(selectionStart, selectionEnd)
        var b = maxOf(selectionStart, selectionEnd)
        if (hasComposing) {
            a = minOf(a, composingStart)
            b = maxOf(b, composingEnd)
        }
        val start: Int
        val end: Int
        if (inCodePoints) {
            // an unpaired surrogate in the way makes the real call delete nothing at all
            start = codePointsBackward(a, before) ?: return
            end = codePointsForward(b, after) ?: return
        } else {
            // negative lengths are ignored, not an error
            start = if (before > 0) (a - before).coerceAtLeast(0) else a
            end = if (after > 0) (b + after).coerceAtMost(buffer.length) else b
        }
        buffer.delete(b, end)
        buffer.delete(start, a)
        val removed = a - start
        fun move(p: Int) = if (p >= a) p - removed else p
        selectionStart = move(selectionStart)
        selectionEnd = move(selectionEnd)
        if (hasComposing) {
            composingStart = move(composingStart)
            composingEnd = move(composingEnd)
        }
    }

    private fun codePointsBackward(from: Int, count: Int): Int? {
        var i = from
        repeat(count) {
            if (i == 0) return i
            val c = buffer[i - 1]
            i -= if (Character.isLowSurrogate(c)) {
                if (i < 2 || !Character.isHighSurrogate(buffer[i - 2])) return null
                2
            } else {
                if (Character.isHighSurrogate(c)) return null
                1
            }
        }
        return i
    }

    private fun codePointsForward(from: Int, count: Int): Int? {
        var i = from
        repeat(count) {
            if (i == buffer.length) return i
            val c = buffer[i]
            i += if (Character.isHighSurrogate(c)) {
                if (i + 1 >= buffer.length || !Character.isLowSurrogate(buffer[i + 1])) return null
                2
            } else {
                if (Character.isLowSurrogate(c)) return null
                1
            }
        }
        return i
    }

    override fun batchEdit(block: () -> Unit) {
        batchDepth++
        maxBatchDepth = maxOf(maxBatchDepth, batchDepth)
        calls += "beginBatchEdit()"
        try {
            block()
        } finally {
            calls += "endBatchEdit()"
            batchDepth--
        }
    }

    /** Simulates the user or the app moving the selection, which the IME then hears about. */
    fun reportSelection(start: Int, end: Int = start) {
        selectionStart = start
        selectionEnd = end
    }
}
