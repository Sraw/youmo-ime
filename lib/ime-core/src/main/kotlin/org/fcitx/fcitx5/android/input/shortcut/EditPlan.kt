/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.input.editing.InputEditor

/**
 * What 复制, 剪切, 清除 and 恢复 do to the field, as steps the app carries out: 复制 and 剪切 on the
 * selection, or with none the whole field; 清除 as [clear] says. Each goes through the editor as an
 * edit of its own, so the app's own undo (撤销, Ctrl+Z) takes it back too.
 */
object EditPlan {

    /** The editor's context menu actions (performContextMenuAction). */
    enum class Menu { SelectAll, Copy, Cut }

    sealed interface Step {
        data class ContextMenu(val action: Menu) : Step
        data class Select(val start: Int, val end: Int) : Step

        /** Over the selection: committed, or with "" deleted. */
        data class Replace(val text: String) : Step

        /** [before] chars before the cursor and [after] after it deleted, from where the editor has it. */
        data class Delete(val before: Int, val after: Int) : Step
    }

    /** The field as read around its selection, each side at most a limit's characters. */
    data class Field(val before: String, val selected: String, val after: String)

    /**
     * A clear to put back: [restore] carried out on the field as the clear [left] it. [typed], when it
     * was what is typed that went (the app types it again), the app's token for it; [restore] then empty.
     * [whole] false where the clear left text before the cursor it never read (an app giving less than
     * asked, or a reported cursor past the text read): the field then matches by its selection and what
     * is after the cursor alone, [left]'s before not known.
     */
    data class Cleared(val left: Field, val restore: List<Step>, val typed: Any? = null, val whole: Boolean = true) {
        /** The field reads [now] as this clear left it. */
        fun leaves(now: Field) = if (whole) left == now else left.selected == now.selected && left.after == now.after
    }

    /** What a clear does ([steps]) and what it took, to put back ([cleared]); null when not kept. */
    data class Clearing(val steps: List<Step>, val cleared: Cleared?)

    /**
     * A copy with the selection at [start]..[end]: of it, the cursor then at its end, as the 文本编辑
     * panel leaves it; of the whole field with nothing selected, the cursor back where it was.
     */
    fun copy(start: Int, end: Int): List<Step> = if (start != end) {
        listOf(Step.ContextMenu(Menu.Copy), maxOf(start, end).let { Step.Select(it, it) })
    } else {
        listOf(Step.ContextMenu(Menu.SelectAll), Step.ContextMenu(Menu.Copy), Step.Select(start, start))
    }

    fun cut(start: Int, end: Int): List<Step> = if (start != end) {
        listOf(Step.ContextMenu(Menu.Cut))
    } else {
        listOf(Step.ContextMenu(Menu.SelectAll), Step.ContextMenu(Menu.Cut))
    }

    /**
     * 清除 with nothing typed, the field read as [field], its selection [start]..[end] as the editor
     * reported it: the selection (either a range or text selected says there is one); else all before the cursor; else all after it. A side read whole
     * goes by its length from the cursor, where the editor has it (the reported cursor may lag or run
     * ahead); never past the text read, so a short read (an app giving less than asked) clears what it
     * gave and keeps it, put back at the cursor; one cut short at [limit] to the field's edge (as far
     * back from the editor's cursor as the report says, the start at most), nothing kept. A selection whose text the app gave
     * none of (or too long to) is deleted and not kept: with no text read it is no empty one, the
     * side before it never cleared in its place. Nothing of a [password] is kept.
     */
    fun clear(field: Field, start: Int, end: Int, limit: Int, password: Boolean): Clearing {
        val (before, selected, after) = field
        val from = minOf(start, end)
        return when {
            // a selection the editor's report has not caught up with (collapsed yet, its text given) goes
            // too, never all before it; not kept, where it starts not known (the text before it an app
            // may cut short whatever it is asked for: review2)
            start != end || selected.isNotEmpty() -> Clearing(
                listOf(Step.Replace("")),
                Cleared(Field(before, "", after), listOf(Step.Select(from, from), Step.Replace(selected), Step.Select(from, from + selected.length)))
                    .takeIf { start != end && selected.isNotEmpty() && selected.length < limit },
            )
            // read cut short at the limit: all up to the cursor goes, nothing kept. A reported cursor past
            // the text given is not this: the report may run ahead of the editor (a tap not reported yet),
            // so only what was read goes, from the editor's own cursor, and stays restorable; an app that
            // gave less than asked leaves the rest for another 清除
            // the field's start, from the editor's own cursor: never a Select from a reported cursor that
            // may run past it, which would take text after the cursor too
            before.length >= limit -> Clearing(listOf(Step.Delete(maxOf(from, before.length), 0)), null)
            // put back where it went, at the cursor: text the app did not give stays before it
            before.isNotEmpty() -> Clearing(
                listOf(Step.Delete(before.length, 0)),
                Cleared(Field("", "", after), listOf(Step.Replace(before)), whole = from == before.length),
            )
            // nothing before the cursor: all the field is after it
            after.length >= limit -> Clearing(listOf(Step.ContextMenu(Menu.SelectAll), Step.Replace("")), null)
            after.isNotEmpty() -> Clearing(
                listOf(Step.Delete(0, after.length)),
                Cleared(Field("", "", ""), listOf(Step.Replace(after), Step.Select(0, 0))),
            )
            else -> Clearing(emptyList(), null)
        }.let { if (password) it.copy(cleared = null) else it }
    }

    /** [steps] carried out on [editor], the context menu's through [menu] (the editor has no such call). */
    fun apply(steps: List<Step>, editor: InputEditor, menu: (Menu) -> Unit) {
        if (steps.isEmpty()) return
        editor.batchEdit {
            steps.forEach {
                when (it) {
                    is Step.ContextMenu -> menu(it.action)
                    is Step.Select -> editor.setSelection(it.start, it.end)
                    is Step.Replace -> editor.commitText(it.text, 1)
                    is Step.Delete -> editor.deleteSurroundingText(it.before, it.after, false)
                }
            }
        }
    }
}

