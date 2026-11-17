/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.input.cursor.CursorRange

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
     * API 35, not at API 29, the minSdk: InputEditorContractTest holds both under Robolectric; 30-34 not
     * checked) then put it back for
     * `newCursorPosition == 0` at a collapsed cursor, so it ends up before the new text rather
     * than carried after it.
     */
    private val reselectsZeroAfterInsert: Boolean = true,
) : InputEditor {

    /**
     * Set to false to model the gap between input sessions, when there is no connection: as
     * `InputConnectionEditor` does then, every call is dropped, a read giving null. It is still
     * recorded in [calls], so a test can tell what was attempted.
     */
    override var isAvailable: Boolean = true

    /**
     * Reads answer, writes are dropped: a WebView that lost its view's focus while the keyboard stayed up
     * (the screen turned, its view no height for a moment). The connection is the served one still, every
     * write is taken (`commitText` true) and does nothing, no selection is reported, and a read gives the
     * text as it was. Each call is still recorded in [calls].
     */
    var dropsWrites: Boolean = false

    /**
     * How much text before the cursor this editor is willing to reveal; null models an editor
     * that reveals none (`getTextBeforeCursor` returning null). Real editors cap it too.
     */
    var revealLimit: Int? = Int.MAX_VALUE

    private val buffer = StringBuilder(initial)

    // who put each character of [buffer] there: [APP] or [KEYBOARD]
    private val owner = StringBuilder(APP.toString().repeat(initial.length))

    /**
     * Who put each character of [text] there, one per character: [APP] (the initial text, the app's
     * edits) or [KEYBOARD] (what the input method's calls put in). Characters alike are told apart by
     * it: a test asserts that the keyboard deleted none of the app's, whatever they are.
     */
    val owners: String get() = owner.toString()

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
     * The app's own edit, not the keyboard's: the whole text set to [text] (a chat's box emptied
     * as it sends), the cursor at its end, no composing span left (as `TextView.setText`). Not
     * recorded in [calls].
     */
    fun appSetsText(text: String) {
        buffer.setLength(0)
        buffer.append(text)
        owner.setLength(0)
        owner.append(APP.toString().repeat(text.length))
        selectionStart = text.length
        selectionEnd = text.length
        clearComposing()
    }

    /**
     * The app's own edit of [from, to) to [text] (a `TextWatcher`'s, a formatter's), as an `Editable`'s
     * `replace` moves its spans (held to it by InputEditorContractTest.appEdits): the composing span,
     * exclusive at both ends, takes in nothing inserted at either end; its start inside the range goes
     * to the new text's end, its end inside it to the new text's start, and a span left empty goes. The
     * selection inside the range lands in proportion in the new text (`SpannableStringBuilder`'s
     * adjustment). Not recorded in [calls].
     */
    fun appReplace(from: Int, to: Int, text: String) {
        val length = text.length
        buffer.replace(from, to, text)
        owner.replace(from, to, APP.toString().repeat(length))
        fun moved(p: Int) = if (p in from + 1..<to) from + (p - from) * length / (to - from) else shift(p, from, to, length)
        selectionStart = moved(selectionStart)
        selectionEnd = moved(selectionEnd)
        if (!hasComposing) return
        val a = when {
            composingStart < from -> composingStart
            composingStart <= to -> from + length
            else -> composingStart - (to - from) + length
        }
        val b = when {
            composingEnd <= from -> composingEnd
            composingEnd < to -> from
            else -> composingEnd - (to - from) + length
        }
        if (a < b) {
            composingStart = a
            composingEnd = b
        } else {
            clearComposing()
        }
    }

    /** The app's: the composing span dropped, the text kept (`finishComposingText` of its own, an `InputFilter` that drops spans). */
    fun appDropSpan() = clearComposing()

    /** The app's: [start]..[end] selected (a double tap, a drag, a select-all), as [reportSelection]. */
    fun appSelect(start: Int, end: Int = start) = reportSelection(start, end)

    /**
     * The app's rewrite of what the keyboard puts in (an `InputFilter`: upper-cased, cut to a length),
     * applied to the whole text after each [commitText] and [setComposingText]; null for none. The
     * selection and the composing span are kept, cut to the new length (a span left empty goes).
     */
    var rewriter: ((String) -> String)? = null

    /**
     * Whether the editor gives its composing region at once ([composingRegion], Android 13's
     * `takeSnapshot`); false: it cannot tell, as before Android 13.
     */
    var snapshots = false

    override fun composingRegion(): CursorRange? {
        if (!snapshots) return null
        calls += "composingRegion()"
        return CursorRange(composingStart, composingEnd)
    }

    /**
     * Runs [rewriter] on the text. Owners are kept by position: this fits a rewriter that only
     * changes the tail (a field that appends, trims or clears); one that rewrites the middle would
     * leave the old owners on the new text (R26 F8353).
     */
    private fun rewrite() {
        val r = rewriter ?: return
        val new = r(buffer.toString())
        buffer.setLength(0)
        buffer.append(new)
        // what is left keeps its owner; anything longer is the app's
        owner.setLength(minOf(owner.length, new.length))
        while (owner.length < new.length) owner.append(APP)
        selectionStart = selectionStart.coerceAtMost(new.length)
        selectionEnd = selectionEnd.coerceAtMost(new.length)
        if (hasComposing) {
            composingEnd = composingEnd.coerceAtMost(new.length)
            if (composingStart >= composingEnd) clearComposing()
        }
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
        owner.replace(a, b, KEYBOARD.toString().repeat(text.length))
        if (reselectsZeroAfterInsert && newCursorPosition == 0 && a == b) cursor = c
        selectionStart = cursor
        selectionEnd = cursor
        if (composing && text.isNotEmpty()) {
            composingStart = a
            composingEnd = a + text.length
        }
    }

    /**
     * Whether a [commitText] of this text fails: false returned and nothing changed, as a binder call
     * that did not fit the editor's buffer. Asked once a call, so it can fail a few times and then not.
     */
    var commitFails: (String) -> Boolean = { false }

    override fun commitText(text: String, newCursorPosition: Int): Boolean {
        calls += "commitText($text, $newCursorPosition)"
        if (!isAvailable || commitFails(text)) return false
        if (dropsWrites) return true
        replaceText(text, newCursorPosition, composing = false)
        rewrite()
        return true
    }

    /** Styling spans are not modelled: only the characters of [text] land in the buffer. */
    override fun setComposingText(text: CharSequence, newCursorPosition: Int) {
        calls += "setComposingText($text, $newCursorPosition)"
        if (!isAvailable || dropsWrites) return
        replaceText(text.toString(), newCursorPosition, composing = true)
        rewrite()
    }

    /** Test shorthand for the common `newCursorPosition = 1`. */
    fun setComposingText(text: String) = setComposingText(text, 1)

    /** Out-of-range positions are ignored, not clamped, as `BaseInputConnection` does. */
    override fun setSelection(start: Int, end: Int) {
        calls += "setSelection($start, $end)"
        if (!isAvailable || dropsWrites) return
        val length = buffer.length
        if (start !in 0..length || end !in 0..length) return
        selectionStart = start
        selectionEnd = end
    }

    override fun finishComposingText() {
        calls += "finishComposingText()"
        if (!isAvailable || dropsWrites) return
        clearComposing()
    }

    /** Clamped into the text and ordered; an empty region means no composing region. */
    override fun setComposingRegion(start: Int, end: Int) {
        calls += "setComposingRegion($start, $end)"
        if (!isAvailable || dropsWrites) return
        val length = buffer.length
        val a = minOf(start, end).coerceIn(0, length)
        val b = maxOf(start, end).coerceIn(0, length)
        clearComposing()
        if (a != b) {
            composingStart = a
            composingEnd = b
        }
    }

    override fun textAfterCursor(length: Int): CharSequence? {
        calls += "textAfterCursor($length)"
        if (!isAvailable) return null
        val limit = revealLimit ?: return null
        val b = maxOf(selectionStart, selectionEnd)
        val n = minOf(length, limit, buffer.length - b)
        return buffer.substring(b, b + maxOf(n, 0))
    }

    override fun textBeforeCursor(length: Int): CharSequence? {
        calls += "textBeforeCursor($length)"
        if (!isAvailable) return null
        val limit = revealLimit ?: return null
        val a = minOf(selectionStart, selectionEnd)
        if (a <= 0) return ""
        val n = minOf(length, limit, a)
        return buffer.substring(a - n, a)
    }

    /**
     * Deletes around the selection -- widened to take in the composing region, so composing
     * text itself is never deleted -- and never the selection itself.
     * Like a real editor, it works the widening out from its own composing region.
     */
    override fun deleteSurroundingText(before: Int, after: Int, inCodePoints: Boolean) {
        calls += "deleteSurroundingText($before, $after, inCodePoints=$inCodePoints)"
        if (!isAvailable || dropsWrites) return
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
        owner.delete(b, end)
        owner.delete(start, a)
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

    companion object {
        /** [owners]: a character the app put there (the initial text too). */
        const val APP = 'a'

        /** [owners]: a character the input method's calls put there. */
        const val KEYBOARD = 'k'
    }
}
