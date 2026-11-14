/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.core.CoreLog
import org.fcitx.fcitx5.android.input.editing.EditingSession.CursorUpdate

/**
 * The [session]'s code in the field ([EditingSession.Kind.CODE], 编码行 输入框里): checked by reading it
 * back, taken away only when proven still ours, else let go of as text (dev/design/061-code-in-field.md
 * §0, §4.3, §6.3, §6.4). The session's own entry points ([EditingSession.writeCode],
 * [EditingSession.leaveComposition], its cursor reports) come here while the code is in the field.
 *
 * Every read is the editor's, around the cursor it has, and so is every delete ([takeAway]): the
 * session's offsets run behind the editor's when its reports come late or not at all, and a region set
 * at those would delete the app's text next to the code (CodeInFieldPropertyTest).
 */
internal class FieldCode(private val session: EditingSession, private val editor: InputEditor) {

    /**
     * Told when the code can no longer be in this field (taken away or let go while the engine keeps
     * it, or a field that cannot be read): the code goes on the floating strip, for the rest of this
     * input (CodeInField.fallBack). Not told when the engine's code ends with it
     * ([CursorUpdate.FocusOutIn], a commit, a leave).
     */
    var fellBack: (() -> Unit)? = null

    /**
     * The field is a URL's or an email's ([EditorTraits.isUri], [EditorTraits.isEmail]): an app's
     * completion selected right after the code is the app's, not the user's selection (§6.5). Set by
     * the service as input starts.
     */
    var inlineCompletion = false

    // the code's first report after it went in checked by a read (§6.3): till then each write reads first
    private var checked = false

    // writes since the code went in with no report checked: an editor that never reports is let go of
    private var unreported = 0

    // each write's cursor and span where either changed, in order, its report to come: the cursor
    // alone does not tell them apart where it stays (a code longer after it), and the older write's
    // report, taken for the latest, would have its span judged as not the code's
    private val writes = ArrayDeque<Written>()
    private var lastWrite: Written? = null

    private val composing get() = session.composing
    private val selection get() = session.selection

    /**
     * Whether the code may be written now, the field's composing text the session's code or none
     * (§6.3). The first: not over voice's or English's composing text nor a selection (§6.2), not in a
     * field that will not be read (no letter goes in, [fellBack] told). Later, before a report checked
     * it: read first, and an editor that never reports is not one to leave it in; let go of, or taken
     * away when proven ours, then. Checked, read first only when the last write is not reported yet.
     */
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
        // the last write reported: as the editor says it is. Else, its report not come by the next key
        // (an app that cut it short reports nothing, its cursor and span as they were), read first
        if (checked && selection.settled && writes.isEmpty()) return true
        unreported++
        val at = selection.latest
        val there = there(at.start, at.end)
        if (there == true && (checked || unreported < MAX_UNREPORTED_WRITES)) {
            // read back alike proves the letters, not the span: an app that ended the composing text (a
            // search box's clearComposingText, posted) before its report came would have the next write
            // put in at the cursor, a copy beside them. Where the editor can say, its region is asked;
            // where not, or with the last write reported, the code as last written is left beside the
            // next: one letter where the app clears at every change, more where it clears after a pause (LC-30)
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

    /** The code written ([EditingSession.writeCode]): where its cursor or span changed, a report to come. */
    fun wrote() {
        val written = Written(selection.latest.start, composing.start, composing.end)
        if (written == lastWrite) return
        lastWrite = written
        writes.addLast(written)
    }

    // whether a report is the latest write's: an older one's dropped with those before it; one no write
    // left (the app's span, none) is the editor's now, what is written since all reported
    private fun latestWrite(cursor: Int, start: Int, end: Int): Boolean {
        val at = writes.indexOf(Written(cursor, start, end))
        if (at < 0) {
            writes.clear()
            return true
        }
        repeat(at + 1) { writes.removeFirst() }
        return writes.isEmpty()
    }

    /**
     * A report while the code is in the field (§4.3, §6.3): it never goes through the rest of the
     * session's reconcile, which would restore a dropped span or move the engine's cursor into a code
     * the app changed. Whatever is not proven ours is let go of, never deleted (§0).
     */
    @Suppress("LongParameterList")
    fun reconcile(
        selStart: Int,
        selEnd: Int,
        composingStart: Int,
        composingEnd: Int,
        ignoreSystemCursor: Boolean,
        updateIndex: Int,
    ): CursorUpdate {
        val ours = composingStart == composing.start && composingEnd == composing.end
        val dropped = composingStart < 0
        // the editor reports: not one that never does, however far behind it is
        unreported = 0
        if (selection.consume(selStart, selEnd)) {
            if (latestWrite(selStart, composingStart, composingEnd) && selection.settled) {
                if (!ours || !checked) firstReport(ours, dropped, selStart, selEnd)
                return CursorUpdate.None
            }
            // an older write's report, more written since: its span and cursor are not the field's now,
            // and a read sized by them would be read and deleted around the editor's cursor as if they
            // were (a report one key behind took the app's text after the code, alike, for the code's
            // end). Nothing judged till the latest write's
            return CursorUpdate.None
        }
        selection.resetTo(selStart, selEnd)
        writes.clear()
        lastWrite = null
        if (selStart != selEnd || !composing.contains(selStart)) session.pairs.forget()
        if (selStart != selEnd) return selected(selStart, selEnd, ours)
        if (!ours && !dropped) {
            // the editor's span another than the code's: a write put in beside the code (五笔's gg after a
            // g whose span the app ended unreported), or the app's own; read back alike proves no span ours
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
            if (ignoreSystemCursor || position == text.cursor) return CursorUpdate.None
            return CursorUpdate.MovePreeditCursor(text.codePointCountUntil(position), updateIndex)
        }
        val start = composing.start
        val end = composing.end
        session.resetComposingState()
        if (ours) {
            // the editor's span in this report is the code: its own composing text replaced, at no
            // offset of the session's (the app may have moved it since, an offset would delete what came
            // there instead); only the cursor is put back where the report has it
            CoreLog.d { "cursor left the code: taken away, focus out/in" }
            val p = if (selStart >= end) selStart - (end - start) else selStart
            selection.predict(p)
            editor.batchEdit {
                editor.setComposingText("", 1)
                editor.setSelection(p, p)
            }
        } else {
            // or a tap inside it the editor reports no span over
            CoreLog.d { "cursor left a code the editor reports no span over: let go, focus out/in" }
            editor.finishComposingText()
        }
        return CursorUpdate.FocusOutIn
    }

    // the first report since the code went in, or the span not ours any more: read once
    private fun firstReport(ours: Boolean, dropped: Boolean, selStart: Int, selEnd: Int) {
        when (there(selStart, selEnd)) {
            true -> if (ours) {
                checked = true
                unreported = 0
            } else if (dropped) {
                // the editor keeps no span over it (an app clearing it, an editor reporting none): the
                // next write would add another copy, so it goes, proven ours, and the code floats
                CoreLog.d { "code's span not the editor's: taken away, the code floats" }
                out(take = true, at = selStart)
            } else {
                // a span other than the code's: the app wrote in it or by it (its text maybe alike, read
                // back as the code's), nothing of it proven ours
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

    // a selection reported none predicted
    private fun selected(selStart: Int, selEnd: Int, ours: Boolean): CursorUpdate {
        // an address bar's own completion, selected after what is typed (§6.5): the code before it stays,
        // the editor's span over it; with none, a commit would replace the selection, the app's text
        val completion = inlineCompletion && ours && selStart == composing.end && selEnd > selStart
        if (completion && there(selStart, selStart) == true) return CursorUpdate.None
        // a selection over or by the code: nothing read (§4.3), the code left as text, the engine's dropped
        CoreLog.d { "a selection while the code is in the field: let go" }
        letGo()
        return CursorUpdate.FocusOutIn
    }

    /** The field left with the code in it ([EditingSession.leaveComposition]); [fallBack], the engine keeps it. */
    fun leave(fallBack: Boolean) {
        if (!editor.isAvailable || composing.isEmpty()) {
            session.resetComposingState()
            return
        }
        val at = selection.latest
        // a selection: nothing read, the code let go of
        val proven = at.isEmpty() && at.start in composing.start..composing.end && there(at.start, at.end) == true
        if (proven) takeAway(at.start) else letGo()
        if (fallBack) fellBack?.invoke()
    }

    /** Input restarted over the code ([EditingSession.codeRestarted]). */
    fun restarted(again: Boolean) {
        if (again) {
            leave(fallBack = false)
            return
        }
        letGo()
        fellBack?.invoke()
    }

    /** [EditingSession.beforeCode]. */
    fun before(before: CharSequence?): CharSequence? {
        if (before == null || !session.codeInField) return before
        val cursor = selection.latest.start
        if (cursor !in composing.start..composing.end) return before
        val drop = cursor - composing.start
        return if (drop > before.length) before.subSequence(0, 0) else before.subSequence(0, before.length - drop)
    }

    /** [EditingSession.afterCode]. */
    fun after(after: CharSequence?): CharSequence? {
        if (after == null || !session.codeInField) return after
        val cursor = selection.latest.end
        if (cursor !in composing.start..composing.end) return after
        val drop = composing.end - cursor
        return if (drop > after.length) after.subSequence(after.length, after.length) else after.subSequence(drop, after.length)
    }

    // the code out of the field: taken away when proven ours ([take]) with the cursor [at] it, else let
    // go of; the engine keeps it and it floats
    private fun out(take: Boolean, at: Int) {
        if (take && at in composing.start..composing.end) takeAway(at) else letGo()
        fellBack?.invoke()
    }

    private fun letGo() {
        session.resetComposingState()
        editor.finishComposingText()
    }

    /**
     * Takes away the code read back as written ([there]) with the session's cursor at [cursor], inside
     * it or at either end: what was read before and after the editor's cursor is what is deleted there.
     */
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

    /**
     * Whether the field still holds the code where it was written, the cursor now at
     * [selStart]..[selEnd]: true only when read back character for character. Null when it cannot
     * be told: the editor will not say, a selection over the code, more than
     * [MAX_STILL_THERE_READ] away; false when what is there differs (§4.3's proof).
     */
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

/** Writes of the code with no report checked after which the editor is taken as one that never reports (§6.3). */
private const val MAX_UNREPORTED_WRITES = 4

private data class Written(val cursor: Int, val start: Int, val end: Int)
