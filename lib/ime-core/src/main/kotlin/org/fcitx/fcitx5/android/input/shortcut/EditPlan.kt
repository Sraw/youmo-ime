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
    }

    /** The field as read around its selection, each side at most a limit's characters. */
    data class Field(val before: String, val selected: String, val after: String)

    /**
     * A clear to put back: [restore] carried out on the field as the clear [left] it. [typed], when it
     * was what is typed that went (the app types it again), the app's token for it; [restore] then empty.
     */
    data class Cleared(val left: Field, val restore: List<Step>, val typed: Any? = null)

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
     * 清除 with nothing typed, the field read as [field] with its selection from [start] (the editor's,
     * so a side cut short at [limit] is cleared all the same): the selection; else all before the
     * cursor; else all after it. Kept to put back when what went was read whole and is no [password].
     */
    fun clear(field: Field, start: Int, limit: Int, password: Boolean): Clearing {
        val (before, selected, after) = field
        return when {
            selected.isNotEmpty() -> Clearing(
                listOf(Step.Replace("")),
                Cleared(Field(before, "", after), listOf(Step.Select(start, start), Step.Replace(selected), Step.Select(start, start + selected.length))),
            )
            before.isNotEmpty() -> Clearing(
                listOf(Step.Select(0, start), Step.Replace("")),
                Cleared(Field("", "", after), listOf(Step.Select(0, 0), Step.Replace(before))).takeIf { before.length < limit },
            )
            after.isNotEmpty() -> Clearing(
                listOf(Step.ContextMenu(Menu.SelectAll), Step.Replace("")),
                Cleared(Field("", "", ""), listOf(Step.Replace(after), Step.Select(0, 0))).takeIf { after.length < limit },
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
                }
            }
        }
    }
}

/**
 * The clears of the field in it, in memory only (a password's never), put back last first ([take])
 * while the field is as the clear left it: once anything else changed it, nothing is put back.
 * Another field's clears are forgotten as one is cleared there.
 */
class ClearedText {

    private var field: String? = null
    private val clears = ArrayDeque<EditPlan.Cleared>()

    /** [field] (the app's name for it) cleared: [cleared] kept, or null when that clear could not be. */
    fun remember(field: String, cleared: EditPlan.Cleared?) {
        if (field != this.field) clears.clear()
        this.field = field
        // one not kept breaks the chain: those before it were left by a field since changed
        if (cleared == null) clears.clear() else clears.addLast(cleared)
    }

    /** The last clear to put back in [field], read as [now]; null, all forgotten, when it changed since. */
    fun take(field: String, now: EditPlan.Field): EditPlan.Cleared? {
        val last = clears.lastOrNull()?.takeIf { field == this.field } ?: return null
        if (last.left != now) {
            clears.clear()
            return null
        }
        return clears.removeLast()
    }
}
