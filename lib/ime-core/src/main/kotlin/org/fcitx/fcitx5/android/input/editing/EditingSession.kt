/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.core.CoreLog
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.input.cursor.CursorRange
import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import kotlin.math.max

/**
 * Where the IME's idea of the cursor, the selection and the composing region lives.
 *
 * The IME runs ahead of the editor: it decides where the cursor *will* be, tells the editor,
 * and later reconciles that prediction against what the editor reports
 * (see [CursorTracker]). Getting that wrong shows up as the cursor jumping, text landing in
 * the wrong place, or a composition that will not clear -- all of which are miserable to
 * reproduce by hand.
 *
 * Everything here is arithmetic over an [InputEditor], with no Android types involved.
 *
 * **Confined to one thread** (the main thread, in the service): the state is plain mutable
 * fields, and the editor's cursor reports arrive on that thread too. [checkThread] is called on
 * every mutating entry point so a debug build can flag a caller on the wrong thread.
 */
class EditingSession(
    private val editor: InputEditor,
    private val checkThread: () -> Unit = {},
) {

    /** Where the IME believes the cursor is, reconciled against the editor's reports. */
    val selection = CursorTracker()

    /** The range currently held as composing text, in editor coordinates. */
    val composing = CursorRange()

    /** The content of that range, as fcitx formatted it. */
    var composingText: FormattedText = FormattedText.Empty
        private set

    /**
     * Records [text] as the composing text without touching the editor -- for putting a session
     * into a known state. [updateComposingText] is what shows a new preedit in the editor.
     */
    fun setComposingText(text: FormattedText) {
        checkThread()
        composingText = text
    }

    /**
     * Counts the editor's cursor reports. A fcitx job queued in response to one report checks it
     * against this before acting, so a burst of reports (a held Backspace, fast typing) only
     * acts on the last. Read from the fcitx thread, hence volatile. Never reset: a stale job
     * from before a reset could otherwise match a fresh report's index.
     */
    @Volatile
    var cursorUpdateIndex: Int = 0
        private set

    /** What the service has to ask fcitx to do after a cursor report; see [onCursorUpdate]. */
    sealed interface CursorUpdate {
        /** Nothing: the report was expected, or is a selection the IME does not act on. */
        data object None : CursorUpdate

        /**
         * The cursor moved with nothing composed here. If fcitx still has something on its input
         * panel (candidates, say), it belongs to where the cursor was, so reset it.
         */
        data object ResetIfNotEmpty : CursorUpdate

        /**
         * The cursor moved within the composition; move fcitx's preedit cursor to match.
         * [codePointPosition] counts code points, as fcitx does. Skip it when [updateIndex] is
         * no longer [cursorUpdateIndex]: a newer report superseded it.
         */
        data class MovePreeditCursor(val codePointPosition: Int, val updateIndex: Int) : CursorUpdate

        /**
         * The cursor left the composition, which has been finished in place here: the editor
         * keeps the text. fcitx has to let go of its preedit too; a reset would commit it at the
         * new cursor position, so the service focuses out and back in instead. With
         * `ClientUnfocusCommit` set, fcitx drops the preedit on focus out without committing.
         */
        data object FocusOutIn : CursorUpdate
    }

    /**
     * Reconciles a cursor report from the editor (`onUpdateSelection`) with the prediction.
     *
     * A report the prediction expected changes nothing -- except that some editors drop the
     * composing span through an `InputFilter`, which is restored here. Anything else is the user
     * moving the cursor, and becomes the new truth; what that means for the composition is
     * returned for the service to pass on to fcitx.
     *
     * @param ignoreSystemCursor the user setting that keeps taps inside the preedit from moving
     * fcitx's cursor
     */
    fun onCursorUpdate(
        selStart: Int,
        selEnd: Int,
        composingStart: Int,
        composingEnd: Int,
        ignoreSystemCursor: Boolean,
    ): CursorUpdate {
        checkThread()
        val updateIndex = ++cursorUpdateIndex
        if (selection.consume(selStart, selEnd)) {
            // Only when the prediction matched: an InputFilter can also change the text, and
            // then the old range may no longer be the composition.
            // https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/widget/Editor.java#2083
            // https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/widget/TextView.java#7351
            restoreComposingRegionIfDropped(composingStart, composingEnd)
            return CursorUpdate.None
        }
        selection.resetTo(selStart, selEnd)
        // a selection (rather than a cursor) leaves the composition to the next cursor report
        if (selStart != selEnd) return CursorUpdate.None
        if (composing.isEmpty()) return CursorUpdate.ResetIfNotEmpty
        if (composing.contains(selStart)) {
            if (ignoreSystemCursor) return CursorUpdate.None
            // fcitx's cursor is relative to the preedit
            val position = selStart - composing.start
            if (position == composingText.cursor) return CursorUpdate.None
            return CursorUpdate.MovePreeditCursor(composingText.codePointCountUntil(position), updateIndex)
        }
        CoreLog.d { "cursor left the composition: finish it in place, focus out/in" }
        resetComposingState()
        editor.finishComposingText()
        return CursorUpdate.FocusOutIn
    }

    /**
     * Shows fcitx's new preedit [text] as the editor's composing text, and predicts where that
     * leaves the cursor.
     *
     * `setComposingText` can only leave the cursor outside the new text (see
     * `BaseInputConnection.replaceText`), so a preedit cursor anywhere else takes a separate
     * `setSelection`; the editor may report the intermediate position first, which the
     * prediction absorbs.
     *
     * @param render the text as the editor should show it (the service adds highlight spans)
     */
    fun updateComposingText(
        text: FormattedText,
        render: (FormattedText) -> CharSequence = { it.toString() },
    ) {
        checkThread()
        if (!editor.isAvailable) return
        val lastSelection = selection.latest
        editor.batchEdit {
            if (composingText.spanEquals(text)) {
                // Same text: only the cursor can have moved, and only a valid one in a
                // non-empty preedit is followed.
                if (text.length > 0 && text.cursor >= 0) {
                    val p = text.cursor + composing.start
                    if (p != lastSelection.start) {
                        selection.predict(p)
                        editor.setSelection(p, p)
                    }
                }
            } else if (text.isEmpty()) {
                // With nothing composed before either, composing.start means nothing; the cursor
                // stays. Otherwise it goes back to where the composition started.
                selection.predict(if (composing.isEmpty()) lastSelection.start else composing.start)
                composing.clear()
                editor.setComposingText("", 1)
            } else {
                val start = if (composing.isEmpty()) lastSelection.start else composing.start
                composing.update(start, start + text.length)
                if (text.cursor == text.length || text.cursor < 0) {
                    // at the end (or no cursor): where setComposingText leaves it anyway
                    selection.predict(composing.end)
                    editor.setComposingText(render(text), 1)
                } else {
                    val p = text.cursor + composing.start
                    selection.predict(p)
                    editor.setComposingText(render(text), 1)
                    editor.setSelection(p, p)
                }
            }
            composingText = text
        }
        CoreLog.d { "composing '$text' at $composing, predicted ${selection.latest}" }
    }

    fun resetComposingState() {
        checkThread()
        composing.clear()
        composingText = FormattedText.Empty
    }

    /**
     * Ends the composition, leaving what was composed in the editor as ordinary text, and
     * forgets it here. Nothing to do when nothing is being composed, or with no editor.
     */
    fun finishComposition() {
        checkThread()
        if (!editor.isAvailable) return
        if (composing.isEmpty()) return
        resetComposingState()
        editor.finishComposingText()
    }

    /**
     * Commits [text], replacing the composing region if there is one, the selection if there
     * is one, or simply inserting before the cursor.
     *
     * @param cursor where to leave the cursor within [text]; `-1` means after its last
     * character, which is the common case and lets the editor place the cursor itself.
     */
    fun commitText(text: String, cursor: Int = -1) {
        checkThread()
        if (!editor.isAvailable) return
        // When the commit is exactly what is already composing, there is nothing to replace:
        // finish the composition as-is and only move the cursor if it is not already there.
        // Re-committing identical text would make the editor redraw and can drop spans.
        if (composing.isNotEmpty() && composingText.toString() == text) {
            val c = if (cursor == -1) text.length else cursor
            val target = composing.start + c
            resetComposingState()
            editor.batchEdit {
                if (selection.current.start != target) {
                    selection.predict(target)
                    editor.setSelection(target, target)
                }
                editor.finishComposingText()
            }
            return
        }
        val start = if (composing.isEmpty()) selection.latest.start else composing.start
        resetComposingState()
        if (cursor == -1) {
            selection.predict(start + text.length)
            editor.commitText(text, 1)
        } else {
            val target = start + cursor
            selection.predict(target)
            editor.batchEdit {
                editor.commitText(text, 1)
                editor.setSelection(target, target)
            }
        }
    }

    /** Deletes the current selection, if any. A collapsed cursor is left alone. */
    fun deleteSelection() {
        checkThread()
        if (!editor.isAvailable) return
        val lastSelection = selection.latest
        if (lastSelection.isEmpty()) return
        selection.predict(lastSelection.start)
        editor.commitText("", 1)
    }

    /**
     * Moves each end of the selection by the given offset, clamped at the start of the buffer.
     * An offset that would invert the range is ignored rather than applied backwards.
     *
     * The end is not clamped at the end of the text, whose length the session does not know;
     * an editor ignores a selection past it, leaving the prediction unconfirmed until the next
     * report. The one caller, the backspace swipe, only ever moves the start.
     */
    fun applySelectionOffset(offsetStart: Int, offsetEnd: Int = 0) {
        checkThread()
        if (!editor.isAvailable) return
        val lastSelection = selection.latest
        val start = max(lastSelection.start + offsetStart, 0)
        val end = max(lastSelection.end + offsetEnd, 0)
        if (start > end) return
        selection.predict(start, end)
        editor.setSelection(start, end)
    }

    /** Collapses a selection to its end, leaving a plain cursor. */
    fun cancelSelection() {
        checkThread()
        if (!editor.isAvailable) return
        val lastSelection = selection.latest
        if (lastSelection.isEmpty()) return
        val end = lastSelection.end
        selection.predict(end)
        editor.setSelection(end, end)
    }

    /**
     * Deletes text around the cursor at fcitx's request.
     *
     * Only [before] shifts the cursor, so only it is predicted -- in UTF-16 units, which is
     * what the editor will report back, even when the deletion itself counts code points.
     * Measuring that costs one synchronous `getTextBeforeCursor`; fcitx asks for surrounding
     * deletions rarely, so this path can afford it (Backspace pays the same round trip, but only
     * for editors that opted in to direct deletion).
     *
     * The service always passes `inCodePoints = true`, as fcitx counts characters; the unit
     * variant is kept because it is the editor's own contract and the tests hold the fake to it.
     */
    fun deleteSurrounding(before: Int, after: Int, inCodePoints: Boolean) {
        checkThread()
        if (!editor.isAvailable) return
        val (overhangBefore, overhangAfter) = composingOverhang()
        if (before > 0) {
            // null: malformed text, which the editor refuses to delete at all
            val units = if (inCodePoints) {
                unitsBeforeCursor(before, overhangBefore) ?: 0
            } else {
                before.coerceAtMost(selection.latest.start - overhangBefore)
            }
            if (units > 0) {
                selection.predictOffset(-units)
                // the deletion is before the composition too, so it moves along
                if (composing.isNotEmpty()) composing.offset(-units)
            }
        }
        editor.deleteSurroundingText(before, after, inCodePoints, overhangBefore, overhangAfter)
    }

    /**
     * How far the composing region sticks out beyond the selection on each side, in units.
     * The editor deletes around the union of the two, never the composing text itself.
     */
    private fun composingOverhang(): Pair<Int, Int> {
        if (composing.isEmpty()) return 0 to 0
        val sel = selection.latest
        return (sel.start - composing.start).coerceAtLeast(0) to (composing.end - sel.end).coerceAtLeast(0)
    }

    /**
     * Handles Backspace from the virtual keyboard.
     *
     * Most editors get a Backspace key event, because that is the only thing every editor
     * understands. Editors that opted in ([EditorTraits.acceptsDeleteSurrounding]) are edited
     * directly instead: the selection is deleted, or else one code point before the cursor.
     *
     * @return true when handled here; false when the caller must send a Backspace key event
     */
    fun backspace(traits: EditorTraits): Boolean {
        checkThread()
        val lastSelection = selection.latest
        var direct = editor.isAvailable && traits.acceptsDeleteSurrounding &&
            !traits.isRawKeyInput && (lastSelection.isNotEmpty() || lastSelection.start > 0)
        // A direct deletion is a known code point, so it is measured -- a round trip per press,
        // but only for editors that opted in. Malformed text (an unpaired surrogate) would be
        // refused by a code-point deletion, so that one goes as a key event, which removes it.
        var directUnits = 1
        if (direct && lastSelection.isEmpty()) {
            val measured = unitsBeforeCursor(1, overhang = 0)
            if (measured == null) direct = false else directUnits = measured
        }
        // With no editor the key event the caller falls back to goes nowhere either, so there
        // is no move to predict.
        if (editor.isAvailable) {
            if (lastSelection.isNotEmpty()) {
                selection.predict(lastSelection.start)
            } else if (lastSelection.start > 0) {
                // A key event deletes whatever the editor thinks one character is, which can
                // only be guessed at; asking the editor would add a round trip to every press,
                // so the guess stays at one unit.
                selection.predictOffset(-(if (direct) directUnits else 1))
            }
        }
        if (!direct) return false
        if (lastSelection.isEmpty()) {
            editor.deleteSurroundingText(1, 0, inCodePoints = true)
        } else {
            editor.commitText("", 0)
        }
        return true
    }

    /**
     * How many UTF-16 units deleting [codePoints] code points before the cursor removes -- and
     * so how far the cursor moves back.
     *
     * The editor deletes before the composing region when it starts before the cursor
     * ([overhang] units of composing text), not before the cursor, so the text is measured from
     * there. When the editor will not show enough text to tell, one unit per code point is
     * assumed.
     *
     * @return null for malformed text (an unpaired surrogate in the way), which the editor
     * refuses to delete, so nothing moves
     */
    private fun unitsBeforeCursor(codePoints: Int, overhang: Int): Int? {
        val cursor = selection.latest.start
        val boundary = cursor - overhang
        if (boundary <= 0) return 0
        // more code points than there are units cannot exist; also keeps the arithmetic small
        val count = codePoints.coerceAtMost(boundary)
        val wanted = count * 2 + overhang // enough for every code point to be a surrogate pair
        val text = editor.textBeforeCursor(wanted) ?: return count
        // Shorter than the cursor position allows means the editor held text back, not that
        // the text starts there; counting what came back would under-predict.
        if (text.length < minOf(wanted, cursor)) return count
        return CodePoints.lengthOfLast(text.subSequence(0, text.length - overhang), count)
    }

    /**
     * Restores the composing region after an editor reported no composing span while this
     * session still believes there is one -- some editors drop it through an `InputFilter`.
     */
    private fun restoreComposingRegionIfDropped(reportedStart: Int, reportedEnd: Int) {
        if (reportedStart != -1 || reportedEnd != -1) return
        if (composing.isEmpty()) return
        editor.setComposingRegion(composing.start, composing.end)
    }
}
