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
 * The IME's prediction of the editor's cursor, selection and composing region, reconciled against
 * the editor's reports ([CursorTracker]). Main thread only: [checkThread] guards every mutating entry.
 */
class EditingSession(
    private val editor: InputEditor,
    private val commitPiece: Int = CommitPieces.MAX,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val checkThread: () -> Unit = {},
) {

    /** Where the IME believes the cursor is, reconciled against the editor's reports. */
    val selection = CursorTracker()

    /** The range currently held as composing text, in editor coordinates. */
    val composing = CursorRange()

    val pairs = AutoPairs(this, editor, checkThread)

    var composingText: FormattedText = FormattedText.Empty
        private set

    /** What the composing text in the field is, and so how it leaves ([leaveComposition]). */
    enum class Kind {
        /** The engine's own preedit in the app (English's word, handwriting's): finished in place as text. */
        TEXT,

        /** Voice's, as it is heard ([provisional]). */
        PROVISIONAL,

        /**
         * The session's code as the code row shows it (编码行 输入框里, [writeCode]): taken away when
         * proven still ours, else let go as text; never kept for the engine to commit.
         */
        CODE,
    }

    var kind = Kind.TEXT
        private set

    /** Text standing in for text still to come (voice's): replaced by the commit, taken away when the cursor leaves. */
    val provisional: Boolean get() = kind == Kind.PROVISIONAL

    val codeInField: Boolean get() = kind == Kind.CODE && composing.isNotEmpty()

    private val code = FieldCode(this, editor)

    /** Whether each commit went in ([CommitCheck]): told of every commit, report and other change made here. */
    val commits = CommitCheck(editor)

    /** The wait for a commit's report is up: where nothing went in, trust the editor's last cursor. */
    fun judgeCommit() {
        checkThread()
        if (commits.judge()) selection.resetTo(selection.current.start, selection.current.end)
    }

    var codeFellBack by code::fellBack

    /** [FieldCode.inlineCompletion]: a URL's or an email's field, its completion after the code the app's. */
    var inlineCompletion by code::inlineCompletion

    /** How a [provisional] composition left the field ([provisionalTaken]). */
    enum class Taken {
        /** the cursor left it or input restarted over it: taken away here, its owner composes it anew */
        AWAY,

        /** the app took it (sent, cleared the field) */
        GONE,

        /** left in the field as text */
        LEFT,
    }

    /** Told when a [provisional] composition is taken from the field by anything but its owner, and how. */
    var provisionalTaken: ((how: Taken) -> Unit)? = null

    /** Records [text] as the composing text without touching the editor; [updateComposingText] shows it. */
    fun setComposingText(text: FormattedText) {
        checkThread()
        composingText = text
    }

    /**
     * Counts cursor reports so a fcitx job queued for one acts only if still the last (fcitx thread reads it).
     * Never reset: a stale job from before a reset could match a fresh index.
     */
    @Volatile
    var cursorUpdateIndex: Int = 0
        private set

    sealed interface CursorUpdate {
        data object None : CursorUpdate

        /** The cursor moved with nothing composed: fcitx's panel belongs to where it was, so reset it. */
        data object ResetIfNotEmpty : CursorUpdate

        /** Move fcitx's preedit cursor ([codePointPosition] in code points) unless [updateIndex] is stale. */
        data class MovePreeditCursor(val codePointPosition: Int, val updateIndex: Int) : CursorUpdate

        /**
         * The cursor left the composition, finished in place here. A reset would commit fcitx's preedit at
         * the new cursor, so focus out and in (`ClientUnfocusCommit` drops it without committing).
         */
        data object FocusOutIn : CursorUpdate
    }

    /** A “ fcitx counts open was left by the last report with nothing composed: fcitx would make the next quote ”. */
    var quoteLeftOpen: Boolean = false
        private set

    /**
     * Reconciles a cursor report with the prediction: an expected one only restores a composing span an
     * `InputFilter` dropped; anything else is the user's move and becomes the truth.
     */
    fun onCursorUpdate(
        selStart: Int,
        selEnd: Int,
        composingStart: Int,
        composingEnd: Int,
    ): CursorUpdate {
        checkThread()
        val quoteOpen = pairs.quoteOpen
        return reconcile(selStart, selEnd, composingStart, composingEnd).also {
            quoteLeftOpen = quoteOpen && !pairs.quoteOpen && composing.isEmpty()
        }
    }

    private fun reconcile(
        selStart: Int,
        selEnd: Int,
        composingStart: Int,
        composingEnd: Int,
    ): CursorUpdate {
        val updateIndex = ++cursorUpdateIndex
        pairs.cursorReported()
        // only the cursor where the last write left it proves that write went in
        if (!selection.settled && selection.latest.rangeEquals(selStart, selEnd)) commits.reported() else commits.moved()
        if (codeInField) return code.reconcile(selStart, selEnd, composingStart, composingEnd, updateIndex)
        if (selection.consume(selStart, selEnd)) {
            // Only when the prediction matched: an InputFilter can change the text too.
            // https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r36/core/java/android/widget/Editor.java#2083
            restoreComposingRegionIfDropped(composingStart, composingEnd)
            return CursorUpdate.None
        }
        selection.resetTo(selStart, selEnd)
        if (provisional && composingStart !in 0..<composingEnd && !provisionalStillThere(selStart, selEnd)) {
            // the app took it: its owner must not put it in again, and the editor's region is not ours to replace
            CoreLog.d { "a provisional composition gone from the field: taken by the app" }
            resetComposingState()
            editor.finishComposingText()
            provisionalTaken?.invoke(Taken.GONE)
            return CursorUpdate.None
        }
        // the pairs survive only a cursor moved within what is composed between them
        if (selStart != selEnd || composing.isEmpty() || !composing.contains(selStart)) pairs.forget()
        if (composing.isEmpty()) return CursorUpdate.ResetIfNotEmpty
        if (selStart != selEnd) return CursorUpdate.None
        if (composing.contains(selStart)) {
            if (provisional) return CursorUpdate.None
            val position = selStart - composing.start
            if (position == composingText.cursor) return CursorUpdate.None
            return CursorUpdate.MovePreeditCursor(composingText.codePointCountUntil(position), updateIndex)
        }
        if (provisional) {
            // many editors report no span at all, so none reported is not taken as gone
            val (start, end) = if (composingStart in 0..<composingEnd) composingStart to composingEnd else composing.start to composing.end
            CoreLog.d { "cursor left a provisional composition: taken away" }
            resetComposingState()
            takeAwayProvisional(start, end, selStart)
            provisionalTaken?.invoke(Taken.AWAY)
            return CursorUpdate.None
        }
        CoreLog.d { "cursor left the composition: finish it in place, focus out/in" }
        resetComposingState()
        editor.finishComposingText()
        return CursorUpdate.FocusOutIn
    }

    /** Where a commit lands now (cursor, or the code it replaces); null over voice's or English's composing text. */
    val commitStart: Int?
        get() = when {
            composing.isEmpty() -> selection.latest.start
            codeInField -> composing.start
            else -> null
        }

    /** Writes the code into the field ([Kind.CODE]), or false and it floats ([codeFellBack] told if it was let go). */
    fun writeCode(text: FormattedText, render: (FormattedText) -> CharSequence = { it.toString() }): Boolean {
        checkThread()
        if (!editor.isAvailable) return false
        if (text.isEmpty()) {
            // never empty the code by an empty write: that replaces whatever the editor holds then
            if (codeInField) code.leave(fallBack = false)
            return true
        }
        if (!code.mayWrite()) return false
        write(text, Kind.CODE, render)
        code.wrote()
        return true
    }

    /** Ends what is composed by its [kind]: text finished, voice's taken away, the code taken away if proven ours. */
    fun leaveComposition(fallBack: Boolean = false) {
        checkThread()
        when (kind) {
            Kind.TEXT -> finishComposition()
            Kind.PROVISIONAL -> updateComposingText(FormattedText.Empty)
            Kind.CODE -> code.leave(fallBack)
        }
    }

    /**
     * Input restarted over the code. [again] (same EditorInfo): taken away when proven ours. Otherwise
     * it may be another field under the same view (WebView, Compose): let go unread, and the code floats.
     */
    fun codeRestarted(again: Boolean) {
        checkThread()
        if (kind == Kind.CODE) code.restarted(again)
    }

    /** A read before the cursor without the code in it: the engine's context never includes the code. */
    fun beforeCode(before: CharSequence?): CharSequence? = code.before(before)

    fun afterCode(after: CharSequence?): CharSequence? = code.after(after)

    /**
     * Input restarted over a [provisional] composition, which the restart's focus out would leave as text:
     * in the same field with a known selection, taken away if still there, else the app took it; otherwise left.
     */
    fun provisionalRestarted(sameField: Boolean, selStart: Int, selEnd: Int) {
        checkThread()
        if (!provisional) return
        val start = composing.start
        val end = composing.end
        val there = sameField && selStart >= 0 && provisionalStillThere(selStart, selEnd)
        resetComposingState()
        if (there) {
            CoreLog.d { "input restarted over a provisional composition: taken away" }
            takeAwayProvisional(start, end, selStart)
        } else {
            editor.finishComposingText()
        }
        provisionalTaken?.invoke(
            when {
                there -> Taken.AWAY
                sameField && selStart >= 0 -> Taken.GONE
                else -> Taken.LEFT
            }
        )
    }

    /** Sets the region first: an editor that dropped its span would otherwise delete only the empty selection. */
    private fun takeAwayProvisional(start: Int, end: Int, selStart: Int) {
        val p = when {
            selStart >= end -> selStart - (end - start)
            selStart > start -> start
            else -> selStart
        }
        selection.predict(p)
        editor.batchEdit {
            editor.setComposingRegion(start, end)
            editor.setComposingText("", 1)
            editor.setSelection(p, p)
        }
    }

    /** Reads around the cursor; an editor that won't say, or one more than [MAX_STILL_THERE_READ] away, counts as there. */
    private fun provisionalStillThere(selStart: Int, selEnd: Int): Boolean {
        val start = composing.start
        val end = composing.end
        if (selStart - start > MAX_STILL_THERE_READ || end - selEnd > MAX_STILL_THERE_READ) return true
        val text: String = when {
            selStart >= end -> {
                val before = editor.textBeforeCursor(selStart - start) ?: return true
                if (before.length < selStart - start) return false
                before.subSequence(0, end - start).toString()
            }
            selEnd <= start -> {
                val after = editor.textAfterCursor(end - selEnd) ?: return true
                if (after.length < end - selEnd) return false
                after.subSequence(start - selEnd, end - selEnd).toString()
            }
            selStart == selEnd -> {
                val before = editor.textBeforeCursor(selStart - start) ?: return true
                val after = editor.textAfterCursor(end - selStart) ?: return true
                if (before.length < selStart - start || after.length < end - selStart) return false
                before.toString() + after.toString()
            }
            else -> return true
        }
        return text == composingText.toString()
    }

    /**
     * Shows fcitx's preedit [text] and predicts the cursor. `setComposingText` can only leave the cursor
     * outside the text, so another position takes a separate `setSelection`.
     */
    fun updateComposingText(
        text: FormattedText,
        provisional: Boolean = false,
        render: (FormattedText) -> CharSequence = { it.toString() },
    ) {
        checkThread()
        if (!editor.isAvailable) return
        write(text, if (provisional) Kind.PROVISIONAL else Kind.TEXT, render)
    }

    private fun write(text: FormattedText, kind: Kind, render: (FormattedText) -> CharSequence) {
        this.kind = if (text.length > 0) kind else Kind.TEXT
        val lastSelection = selection.latest
        // an empty batch is still two calls on the editor; an app switch's empty preedit races the next bind
        val cursorMoves = text.length > 0 && text.cursor >= 0 && text.cursor + composing.start != lastSelection.start
        if (composingText.spanEquals(text) && !cursorMoves) return
        editor.batchEdit {
            if (composingText.spanEquals(text)) {
                if (text.length > 0 && text.cursor >= 0) {
                    val p = text.cursor + composing.start
                    if (p != lastSelection.start) {
                        selection.predict(p)
                        editor.setSelection(p, p)
                    }
                }
            } else if (text.isEmpty()) {
                selection.predict(if (composing.isEmpty()) lastSelection.start else composing.start)
                composing.clear()
                editor.setComposingText("", 1)
            } else {
                val start = if (composing.isEmpty()) lastSelection.start else composing.start
                composing.update(start, start + text.length)
                if (text.cursor == text.length || text.cursor < 0) {
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
        // length only: the exported debug log must not hold what the user typed
        CoreLog.d { "composing ${text.length} chars at $composing, predicted ${selection.latest}" }
    }

    fun resetComposingState() {
        checkThread()
        composing.clear()
        composingText = FormattedText.Empty
        kind = Kind.TEXT
    }

    /** Leaves the composition in the editor as text and forgets it here. */
    fun finishComposition() {
        checkThread()
        if (!editor.isAvailable) return
        if (composing.isEmpty()) return
        resetComposingState()
        editor.finishComposingText()
    }

    /** Replaces [before]/[after] units around the cursor with [text] in one change the editor sees whole. */
    fun replaceAround(before: Int, after: Int, text: String, cursor: Int = -1) {
        checkThread()
        if (!editor.isAvailable) return
        editor.batchEdit {
            if (before > 0 || after > 0) deleteSurrounding(before, after, inCodePoints = false)
            commitText(text, cursor)
        }
    }

    /**
     * Commits [text] over the composing region or selection; a long text goes in pieces in one batch
     * ([CommitPieces]). Returns what went in ([text], or its start if cut short); "" with no editor.
     */
    fun commitText(text: String, cursor: Int = -1): String {
        checkThread()
        if (!editor.isAvailable) return ""
        // committing what is already composed only finishes it: re-committing redraws and can drop spans.
        // Checked only where the editor has said where its cursor is: one without text of its own reads back empty.
        val plain = composing.isEmpty()
        val before = if (selection.latest.start >= 0) commits.before(text, selection.latest.start, plain) else null
        if (composing.isNotEmpty() && composingText.toString() == text) {
            val c = if (cursor == -1) text.length else cursor
            val target = composing.start + c
            resetComposingState()
            editor.batchEdit {
                if (selection.latest.start != target) {
                    selection.predict(target)
                    editor.setSelection(target, target)
                }
                editor.finishComposingText()
            }
            commits.committed(text, before)
            return text
        }
        val start = if (composing.isEmpty()) selection.latest.start else composing.start
        resetComposingState()
        val pieces = CommitPieces.split(text, commitPiece)
        if (pieces.size > 1) {
            return text.substring(0, commitPieces(start, pieces, cursor)).also { commits.committed(it, before) }
        } else if (cursor == -1) {
            selection.predict(start + text.length)
            editor.commitText(text, 1)
            commits.committed(text, before, if (plain) start + text.length else -1)
            return text
        } else {
            val target = start + cursor
            selection.predict(target)
            editor.batchEdit {
                editor.commitText(text, 1)
                editor.setSelection(target, target)
            }
        }
        commits.committed(text, before)
        return text
    }

    // Each piece's end predicted once taken: the editor may report between pieces. A refused piece is
    // retried after a pause; once out of pauses the rest is dropped (a hole would be worse than a short paste).
    private fun commitPieces(start: Int, pieces: List<String>, cursor: Int): Int {
        val began = System.nanoTime()
        var end = start
        editor.batchEdit {
            var paused = 0L
            var retries = 0
            for ((i, piece) in pieces.withIndex()) {
                if (i > 0) pause(CommitPieces.GAP_MS)
                var wait = CommitPieces.FIRST_RETRY_MS
                // false is a full binder buffer or a dead connection, alike. A dead one hides from isAvailable while
                // this loop holds the main thread, so wait out the pauses rather than cut short on the first false.
                while (!editor.commitText(piece, 1)) {
                    if (!editor.isAvailable || paused + wait > CommitPieces.RETRY_BUDGET_MS) {
                        val all = pieces.sumOf { it.length }
                        CoreLog.e("long commit cut short: ${end - start} of $all units, piece ${i + 1} not taken")
                        // the first piece replaced the composition: the editor's would linger
                        if (i == 0) editor.finishComposingText()
                        return@batchEdit
                    }
                    pause(wait)
                    paused += wait
                    retries++
                    wait = minOf(wait * 2, CommitPieces.LAST_RETRY_MS)
                }
                end += piece.length
                selection.predict(end)
            }
            CoreLog.d {
                val ms = (System.nanoTime() - began) / 1_000_000
                "long commit: ${end - start} units, ${pieces.size} pieces, $retries sent again, ${ms}ms in all"
            }
            if (cursor != -1) {
                selection.predict(start + cursor)
                editor.setSelection(start + cursor, start + cursor)
            }
        }
        return end - start
    }

    fun deleteSelection() {
        checkThread()
        if (!editor.isAvailable) return
        val lastSelection = selection.latest
        if (lastSelection.isEmpty()) return
        commits.moved()
        pairs.forget()
        selection.predict(lastSelection.start)
        if (composing.isEmpty()) {
            editor.commitText("", 1)
            return
        }
        // a commit replaces the composing region, not the selection, so finish the composition first
        resetComposingState()
        editor.batchEdit {
            editor.finishComposingText()
            editor.commitText("", 1)
        }
    }

    /**
     * Moves each end of the selection, clamped at 0; an inverting offset is ignored. The end is not
     * clamped at the text's end (unknown here): the editor ignores a selection past it.
     */
    fun applySelectionOffset(offsetStart: Int, offsetEnd: Int = 0) {
        checkThread()
        if (!editor.isAvailable) return
        val lastSelection = selection.latest
        val start = max(lastSelection.start + offsetStart, 0)
        val end = max(lastSelection.end + offsetEnd, 0)
        if (start > end) return
        commits.moved()
        selection.predict(start, end)
        editor.setSelection(start, end)
    }

    /** Selects [start]..[end] as 滑选 moves it: from where it began, not offsets from a report that may lag. */
    fun select(start: Int, end: Int) {
        checkThread()
        if (!editor.isAvailable) return
        val from = max(start, 0)
        val to = max(end, from)
        commits.moved()
        selection.predict(from, to)
        editor.setSelection(from, to)
    }

    /** Selects with [head] as the end the editor moves with shift+arrow; reported the other way if [head] is left. */
    fun selectFrom(anchor: Int, head: Int) {
        checkThread()
        if (!editor.isAvailable) return
        commits.moved()
        selection.predict(anchor, head)
        editor.setSelection(anchor, head)
    }

    fun cancelSelection() {
        checkThread()
        if (!editor.isAvailable) return
        val lastSelection = selection.latest
        if (lastSelection.isEmpty()) return
        val end = lastSelection.end
        commits.moved()
        selection.predict(end)
        editor.setSelection(end, end)
    }

    /**
     * Deletes around the cursor at fcitx's request; only [before] moves the cursor, predicted in UTF-16 units
     * (one synchronous read when counting code points). The service counts units only for text it put in itself
     * where the field must not be read (a password).
     */
    fun deleteSurrounding(before: Int, after: Int, inCodePoints: Boolean) {
        checkThread()
        if (!editor.isAvailable) return
        commits.moved()
        if (after > 0 || composing.isNotEmpty()) pairs.forget()
        val overhangBefore = composingOverhang()
        if (before > 0) {
            // null: malformed text, which the editor refuses to delete
            val units = if (inCodePoints) {
                unitsBeforeCursor(before, overhangBefore) ?: 0
            } else {
                before.coerceAtMost(selection.latest.start - overhangBefore)
            }
            pairs.deletedBefore(selection.latest.start, units)
            if (units > 0) {
                selection.predictOffset(-units)
                if (composing.isNotEmpty()) composing.offset(-units)
            }
        }
        editor.deleteSurroundingText(before, after, inCodePoints)
    }

    /** How far the composing region sticks out before the selection: the editor deletes around the union. */
    private fun composingOverhang(): Int {
        if (composing.isEmpty()) return 0
        return (selection.latest.start - composing.start).coerceAtLeast(0)
    }

    /**
     * Backspace: a key event for most editors; those with [EditorTraits.acceptsDeleteSurrounding] are edited
     * directly. False when the caller must send the key event.
     */
    fun backspace(traits: EditorTraits): Boolean {
        checkThread()
        // fcitx passes Backspace on only without a preedit, so a composing region left here is stale
        if (kind == Kind.CODE) code.leave(fallBack = false) else finishComposition()
        // a deletion: a pending commit can no longer be read back
        commits.moved()
        val lastSelection = selection.latest
        var direct = editor.isAvailable && traits.acceptsDeleteSurrounding &&
            !traits.isRawKeyInput && (lastSelection.isNotEmpty() || lastSelection.start > 0)
        // malformed text (an unpaired surrogate) is refused by a code-point deletion, so it goes as a key event
        var directUnits = 1
        if (direct && lastSelection.isEmpty()) {
            val measured = unitsBeforeCursor(1, overhang = 0)
            if (measured == null) direct = false else directUnits = measured
        }
        if (direct && lastSelection.isNotEmpty()) {
            deleteSelection()
            return true
        }
        if (editor.isAvailable) {
            if (lastSelection.isNotEmpty()) {
                selection.predict(lastSelection.start)
            } else if (lastSelection.start > 0) {
                // a key event deletes what the editor thinks one character is; asking would cost a round trip per press
                selection.predictOffset(-(if (direct) directUnits else 1))
            }
        }
        if (!direct) return false
        editor.deleteSurroundingText(1, 0, inCodePoints = true)
        return true
    }

    /**
     * UTF-16 units [codePoints] code points before the cursor span, measured from the composing region
     * when it starts earlier (the editor deletes before that). Null for malformed text the editor refuses.
     */
    private fun unitsBeforeCursor(codePoints: Int, overhang: Int): Int? {
        val cursor = selection.latest.start
        val boundary = cursor - overhang
        if (boundary <= 0) return 0
        val count = codePoints.coerceAtMost(boundary)
        val wanted = count * 2 + overhang // enough for every code point to be a surrogate pair
        val text = editor.textBeforeCursor(wanted) ?: return count
        // shorter than the cursor allows means the editor held text back
        if (text.length < minOf(wanted, cursor)) return count
        return CodePoints.lengthOfLast(text.subSequence(0, text.length - overhang), count)
    }

    /** Some editors drop the composing span through an `InputFilter`; restore it. */
    private fun restoreComposingRegionIfDropped(reportedStart: Int, reportedEnd: Int) {
        if (reportedStart != -1 || reportedEnd != -1) return
        if (composing.isEmpty()) return
        editor.setComposingRegion(composing.start, composing.end)
    }
}

internal const val MAX_STILL_THERE_READ = 4096

