/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.core.CoreLog
import org.fcitx.fcitx5.android.input.editing.EditingSession.CursorUpdate

/**
 * The session's code in the field (编码行 输入框里): checked by reading it back, taken away only when proven
 * ours, else let go of as text. Every read and delete is around the editor's cursor: the session's offsets lag
 * late reports, and a region set at them would delete the app's text (CodeInFieldPropertyTest).
 */
internal class FieldCode(private val session: EditingSession, private val editor: InputEditor) {

    /** The code can no longer be in this field, and floats for the rest of the input; not told when the engine's code ends with it. */
    var fellBack: (() -> Unit)? = null

    /** A URL's or email's field: an app's completion selected right after the code is the app's, not the user's. */
    var inlineCompletion = false

    // till the code's first report is checked by a read, each write reads first
    private var checked = false

    // an editor that never reports is let go of
    private var unreported = 0

    // each write's cursor and span, to come: the cursor alone cannot tell an older write's report from the latest
    private val writes = ArrayDeque<Written>()
    private var lastWrite: Written? = null

    private val composing get() = session.composing
    private val selection get() = session.selection

    /** Not over voice's or English's text, a selection, or an unreadable field; before a checked report, read first. */
    fun mayWrite(): Boolean {
        if (!session.codeInField) {
            if (composing.isNotEmpty() || selection.latest.isNotEmpty()) return false
            if (editor.textBeforeCursor(1) == null) {
                CoreLog.d { "a field that cannot be read: the code floats" }
                fellBack?.invoke()
                return false
            }
            checked = false
            unreported = 0
            writes.clear()
            lastWrite = null
            return true
        }
        // else its report not come by the next key (an app that cut it short reports nothing): read first
        if (checked && selection.settled && writes.isEmpty()) return true
        unreported++
        val at = selection.latest
        val there = there(at.start, at.end)
        if (there == true && (checked || unreported < MAX_UNREPORTED_WRITES)) {
            // read back alike proves the letters, not the span: an app that ended the composing text before its report
            // would have the next write beside them, so ask the region where the editor can say
            val region = editor.composingRegion()
            if (region == null || region.start == composing.start && region.end == composing.end) return true
            CoreLog.d { "the editor's composing region ${region.start}..${region.end} is not the code's: the code floats" }
            // gone: the letters read back are ours, taken away; another span: not ours to judge, let go of
            out(take = region.start < 0, at = at.start)
            return false
        }
        CoreLog.d { "code unreported or not as written before a write: the code floats" }
        out(take = there == true, at = at.start)
        return false
    }

    fun wrote() {
        val written = Written(selection.latest.start, composing.start, composing.end)
        if (written == lastWrite) return
        lastWrite = written
        writes.addLast(written)
    }

    // an older write's report is dropped; one no write left (the app's span) is the editor's own
    private fun latestWrite(cursor: Int, start: Int, end: Int): Boolean {
        val at = writes.indexOf(Written(cursor, start, end))
        if (at < 0) {
            writes.clear()
            return true
        }
        repeat(at + 1) { writes.removeFirst() }
        return writes.isEmpty()
    }

    /** Never through the session's reconcile (which would restore a span or move the engine's cursor); what is not proven ours is let go of, never deleted. */
    fun reconcile(
        selStart: Int,
        selEnd: Int,
        composingStart: Int,
        composingEnd: Int,
        updateIndex: Int,
    ): CursorUpdate {
        val ours = composingStart == composing.start && composingEnd == composing.end
        val dropped = composingStart < 0
        unreported = 0
        if (selection.consume(selStart, selEnd)) {
            if (latestWrite(selStart, composingStart, composingEnd) && selection.settled) {
                if (!ours || !checked) firstReport(ours, dropped, selStart, selEnd)
                return CursorUpdate.None
            }
            // an older write's report: a read sized by it would delete around the editor's cursor as if it were current
            return CursorUpdate.None
        }
        selection.resetTo(selStart, selEnd)
        writes.clear()
        lastWrite = null
        if (selStart != selEnd || !composing.contains(selStart)) session.pairs.forget()
        if (selStart != selEnd) return selected(selStart, selEnd, ours)
        if (!ours && !dropped) {
            // a write put in beside the code (五笔's gg after a g the app ended unreported), or the app's own
            CoreLog.d { "the editor's span not the code's after a report none predicted: let go, the code floats" }
            out(take = false, at = selStart)
            return CursorUpdate.None
        }
        if (there(selStart, selEnd) != true) {
            // the app changed the text around it (a rewrite, a truncation): not ours to touch, the engine keeps its code
            CoreLog.d { "code not as written after a report none predicted: let go, the code floats" }
            out(take = false, at = selStart)
            return CursorUpdate.None
        }
        // inside it by the editor's own span: with none, the app's text may stand where the code was read
        if (ours && composing.contains(selStart)) {
            val text = session.composingText
            val position = selStart - composing.start
            if (position == text.cursor) return CursorUpdate.None
            return CursorUpdate.MovePreeditCursor(text.codePointCountUntil(position), updateIndex)
        }
        val start = composing.start
        val end = composing.end
        session.resetComposingState()
        if (ours) {
            // the editor's span is the code: replaced at no session offset (the app may have moved it); the cursor put back
            CoreLog.d { "cursor left the code: taken away, focus out/in" }
            val p = if (selStart >= end) selStart - (end - start) else selStart
            selection.predict(p)
            editor.batchEdit {
                editor.setComposingText("", 1)
                editor.setSelection(p, p)
            }
        } else {
            CoreLog.d { "cursor left a code the editor reports no span over: let go, focus out/in" }
            editor.finishComposingText()
        }
        return CursorUpdate.FocusOutIn
    }

    private fun firstReport(ours: Boolean, dropped: Boolean, selStart: Int, selEnd: Int) {
        when (there(selStart, selEnd)) {
            true -> if (ours) {
                checked = true
                unreported = 0
            } else if (dropped) {
                // the editor keeps no span over it: the next write would add a copy, so it goes, and the code floats
                CoreLog.d { "code's span not the editor's: taken away, the code floats" }
                out(take = true, at = selStart)
            } else {
                // a span other than the code's: nothing of it proven ours
                CoreLog.d { "code's span the editor's other one: let go, the code floats" }
                out(take = false, at = selStart)
            }
            // the app changed it (upper-cased, cut short) or will not say: left as it is now
            else -> {
                CoreLog.d { "code not as written at its first report: let go, the code floats" }
                out(take = false, at = selStart)
            }
        }
    }

    private fun selected(selStart: Int, selEnd: Int, ours: Boolean): CursorUpdate {
        // an address bar's own completion after what is typed: the code before it stays
        val completion = inlineCompletion && ours && selStart == composing.end && selEnd > selStart
        if (completion && there(selStart, selStart) == true) return CursorUpdate.None
        // a selection over or by the code: nothing read, the code left as text, the engine's dropped
        CoreLog.d { "a selection while the code is in the field: let go" }
        letGo()
        return CursorUpdate.FocusOutIn
    }

    /** [fallBack]: the engine keeps it. */
    fun leave(fallBack: Boolean) {
        if (!editor.isAvailable || composing.isEmpty()) {
            session.resetComposingState()
            return
        }
        val at = selection.latest
        val proven = at.isEmpty() && at.start in composing.start..composing.end && there(at.start, at.end) == true
        if (proven) takeAway(at.start) else letGo()
        if (fallBack) fellBack?.invoke()
    }

    fun restarted(again: Boolean) {
        if (again) {
            leave(fallBack = false)
            return
        }
        letGo()
        fellBack?.invoke()
    }

    fun before(before: CharSequence?): CharSequence? {
        if (before == null || !session.codeInField) return before
        val cursor = selection.latest.start
        if (cursor !in composing.start..composing.end) return before
        val drop = cursor - composing.start
        return if (drop > before.length) before.subSequence(0, 0) else before.subSequence(0, before.length - drop)
    }

    fun after(after: CharSequence?): CharSequence? {
        if (after == null || !session.codeInField) return after
        val cursor = selection.latest.end
        if (cursor !in composing.start..composing.end) return after
        val drop = composing.end - cursor
        return if (drop > after.length) after.subSequence(after.length, after.length) else after.subSequence(drop, after.length)
    }

    // the engine keeps it and it floats
    private fun out(take: Boolean, at: Int) {
        if (take && at in composing.start..composing.end) takeAway(at) else letGo()
        fellBack?.invoke()
    }

    private fun letGo() {
        session.resetComposingState()
        editor.finishComposingText()
    }

    /** Deletes what was read either side of the editor's cursor: the session's [cursor] lies inside or at an end. */
    private fun takeAway(cursor: Int) {
        val before = cursor - composing.start
        val after = composing.end - cursor
        session.resetComposingState()
        // a selection after it (an address bar's completion) kept, moved back with the text
        selection.predictOffset(-before)
        editor.batchEdit {
            editor.finishComposingText()
            editor.deleteSurroundingText(before, after, inCodePoints = false)
        }
    }

    /** True only when read back character for character; null when it cannot be told, more than [MAX_STILL_THERE_READ] away. */
    private fun there(selStart: Int, selEnd: Int): Boolean? {
        val start = composing.start
        val end = composing.end
        if (selStart - start > MAX_STILL_THERE_READ || end - selEnd > MAX_STILL_THERE_READ) return null
        val text: String = when {
            selStart >= end -> {
                val before = editor.textBeforeCursor(selStart - start) ?: return null
                if (before.length < selStart - start) return false
                before.subSequence(0, end - start).toString()
            }
            selEnd <= start -> {
                val after = editor.textAfterCursor(end - selEnd) ?: return null
                if (after.length < end - selEnd) return false
                after.subSequence(start - selEnd, end - selEnd).toString()
            }
            selStart == selEnd -> {
                val before = editor.textBeforeCursor(selStart - start) ?: return null
                val after = editor.textAfterCursor(end - selStart) ?: return null
                if (before.length < selStart - start || after.length < end - selStart) return false
                before.toString() + after.toString()
            }
            else -> return null
        }
        return text == session.composingText.toString()
    }
}

private const val MAX_UNREPORTED_WRITES = 4

private data class Written(val cursor: Int, val start: Int, val end: Int)
