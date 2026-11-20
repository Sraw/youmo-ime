/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.input.editing.InputEditor

/** 复制, 剪切, 清除 and 恢复 as editor steps, each its own edit so the app's undo takes it back too. */
object EditPlan {

    enum class Menu { SelectAll, Copy, Cut }

    sealed interface Step {
        data class ContextMenu(val action: Menu) : Step
        data class Select(val start: Int, val end: Int) : Step

        data class Replace(val text: String) : Step

        /** From where the editor has the cursor. */
        data class Delete(val before: Int, val after: Int) : Step
    }

    data class Field(val before: String, val selected: String, val after: String)

    /**
     * [typed]: the app's token when what was typed went (the app retypes it; [restore] empty). [whole] false: text
     * before the cursor was never read, so the field matches by its selection and after-text alone.
     */
    data class Cleared(
        val left: Field,
        val restore: List<Step>,
        val typed: Any? = null,
        val whole: Boolean = true,
        val cursorBefore: Boolean = false,
    ) {
        fun leaves(now: Field) = if (whole) left == now else left.selected == now.selected && left.after == now.after

        /**
         * [cursorBefore] is applied at the cursor reported now, not at the clear: that report may have lagged, and no
         * edit says "before what I put in" for every editor (commitText's 0 leaves it after the text on Android 10).
         */
        fun restoreAt(cursor: Int): List<Step> =
            if (!cursorBefore || cursor < 0) restore else restore.map { if (it is Step.Select) Step.Select(cursor, cursor) else it }
    }

    /** Null when not kept. */
    data class Clearing(val steps: List<Step>, val cleared: Cleared?)

    /** Nothing selected and the field read empty: say so, never copy empty. A field not read, or of raw keys, is no empty one. */
    fun nothingToTake(start: Int, end: Int, field: Field?, rawKeys: Boolean = false): Boolean =
        !rawKeys && start == end && field != null && field.before.isEmpty() && field.selected.isEmpty() && field.after.isEmpty()

    /** With nothing selected the whole field, the cursor back where it was. */
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
     * 清除 with nothing typed: the selection, else all before the cursor, else all after. A side goes by its length
     * from the editor's cursor (the reported one may lag or run ahead), never past the text read; one read cut at
     * [limit] goes to the field's edge, not kept. A selection whose text was not given is deleted, not kept. Nothing
     * of a [password] is kept. A side not given ([beforeGiven], [afterGiven] false) is never cleared.
     */
    @Suppress("LongParameterList")
    fun clear(
        field: Field,
        start: Int,
        end: Int,
        limit: Int,
        password: Boolean,
        beforeGiven: Boolean = true,
        afterGiven: Boolean = true,
    ): Clearing {
        val (before, selected, after) = field
        val from = minOf(start, end)
        return when {
            // a selection the report has not caught up with goes too; not kept, its start unknown
            start != end || selected.isNotEmpty() -> Clearing(
                listOf(Step.Replace("")),
                Cleared(Field(before, "", after), listOf(Step.Select(from, from), Step.Replace(selected), Step.Select(from, from + selected.length)))
                    .takeIf { start != end && selected.isNotEmpty() && selected.length < limit },
            )
            // cut at the limit: all up to the cursor goes, nothing kept. Never a Select from a reported cursor that may
            // run past the editor's, which would take text after it too
            before.length >= limit -> Clearing(listOf(Step.Delete(maxOf(from, before.length), 0)), null)
            // put back where it went, at the cursor: text the app did not give stays before it
            before.isNotEmpty() -> Clearing(
                listOf(Step.Delete(before.length, 0)),
                Cleared(Field("", "", after), listOf(Step.Replace(before)), whole = from == before.length),
            )
            // a select-all only where the read reached the start; else only what was read after goes
            after.length >= limit -> Clearing(
                if (beforeGiven && from == 0) listOf(Step.ContextMenu(Menu.SelectAll), Step.Replace("")) else listOf(Step.Delete(0, after.length)),
                null,
            )
            // nothing read before a cursor past the start is a short read too (the field matched with [Cleared.whole] false)
            after.isNotEmpty() -> Clearing(
                listOf(Step.Delete(0, after.length)),
                Cleared(Field("", "", ""), listOf(Step.Replace(after), Step.Select(from, from)), whole = from == 0, cursorBefore = true),
            )
            else -> Clearing(emptyList(), null)
        }.let {
            when {
                password -> it.copy(cleared = null)
                beforeGiven && afterGiven -> it
                else -> it.copy(cleared = it.cleared?.copy(whole = false))
            }
        }
    }

    /** The context menu's steps go through [menu]: the editor has no such call. */
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

