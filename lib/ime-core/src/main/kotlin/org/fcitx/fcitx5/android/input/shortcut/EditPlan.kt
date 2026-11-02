/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.input.editing.InputEditor

/**
 * What 复制, 剪切, 清空 and 恢复 do to the field, as steps the app carries out: with something selected,
 * on it; with nothing, on the whole field, the cursor left where it was. Each goes through the
 * editor as an edit of its own, so the app's own undo (撤销, Ctrl+Z) takes it back.
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

    /** What a field held as it was cleared, and its selection: put back by [restore]. */
    data class Cleared(val text: String, val start: Int, val end: Int)

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
     * A clear of the whole field, read as [before] the selection, [selected] and [after] it (null where
     * the editor gave nothing), each asked for at most [limit] characters. Kept to put back only when
     * read whole (one as long as [limit] may have been cut short) and not a [password]'s: else the
     * field is cleared all the same, with nothing to put back.
     */
    @Suppress("LongParameterList")
    fun clear(before: CharSequence?, selected: CharSequence?, after: CharSequence?, limit: Int, password: Boolean): Clearing {
        val parts = listOfNotNull(before, selected, after)
        if (password || parts.size < 3 || parts.any { it.length >= limit }) {
            return Clearing(listOf(Step.ContextMenu(Menu.SelectAll), Step.Replace("")), null)
        }
        val text = parts.joinToString("")
        if (text.isEmpty()) return Clearing(emptyList(), null)
        val (start, length) = parts[0].length to parts[1].length
        val cleared = Cleared(text, start, start + length)
        return Clearing(listOf(Step.Select(0, text.length), Step.Replace("")), cleared)
    }

    /** [cleared] put back in its now empty field, its selection as it was. */
    fun restore(cleared: Cleared): List<Step> =
        listOf(Step.Replace(cleared.text), Step.Select(cleared.start, cleared.end))

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
 * The field last cleared and what it held, in memory only (a password's never): put back
 * ([take]) in that field while it is still empty. Once text is typed there, 撤销 is what takes the
 * clear back; another field's text is never put in this one.
 */
class ClearedText {

    private var field: String? = null
    private var cleared: EditPlan.Cleared? = null

    /** [field] (the app's name for it) cleared of [cleared]; null forgets what was kept, a clear not kept. */
    fun remember(field: String, cleared: EditPlan.Cleared?) {
        this.field = field
        this.cleared = cleared
    }

    /** What to put back in [field], [empty] now or not: taken, so a second 恢复 puts nothing in. */
    fun take(field: String, empty: Boolean): EditPlan.Cleared? {
        if (field != this.field || !empty) return null
        return cleared.also { cleared = null }
    }
}
