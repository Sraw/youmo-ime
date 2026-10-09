/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

/**
 * The parts of the focused editor's `EditorInfo` that decide how Backspace, Return and the
 * arrow keys act, decoded out of their bit fields.
 *
 * Keeping the decoding on the Android side and the decisions here means the decisions can be
 * tested for every kind of editor without constructing one.
 */
data class EditorTraits(
    /**
     * `TYPE_NULL`: the editor is not a text field (a terminal, a game), so keys must reach it
     * as raw key events rather than as text edits.
     */
    val isRawKeyInput: Boolean = false,
    /** A URL field. Browsers use the arrow keys there to accept an address-bar suggestion. */
    val isUri: Boolean = false,
    /**
     * The editor opted in, through `privateImeOptions`, to having Backspace done as an edit
     * rather than a key event. Almost nothing does: our own key-binding preference is the one
     * known user.
     */
    val acceptsDeleteSurrounding: Boolean = false,
    /** `IME_FLAG_NO_ENTER_ACTION`: Return must stay a plain Enter. */
    val noEnterAction: Boolean = false,
    /** The id of an action the editor labelled itself, or null when it has none. */
    val customActionId: Int? = null,
    /** The action from `imeOptions`, or null when it is `UNSPECIFIED` or `NONE`. */
    val imeAction: Int? = null,
)

/** Decisions for keys whose meaning depends on the editor. */
object EditorKeyPolicy {

    sealed interface ReturnAction {
        /** Send a plain Enter key event; the editor decides what a newline means. */
        data object SendEnter : ReturnAction

        /** Run the editor's action (search, send, go, ...) instead of inserting a newline. */
        data class PerformEditorAction(val actionId: Int) : ReturnAction
    }

    fun onReturn(traits: EditorTraits): ReturnAction = when {
        traits.isRawKeyInput || traits.noEnterAction -> ReturnAction.SendEnter
        // a label the editor chose for itself beats the generic action in imeOptions
        traits.customActionId != null -> ReturnAction.PerformEditorAction(traits.customActionId)
        traits.imeAction != null -> ReturnAction.PerformEditorAction(traits.imeAction)
        else -> ReturnAction.SendEnter
    }

    enum class Direction { Left, Right }

    sealed interface ArrowAction {
        /** Pass the arrow through as a key event. */
        data object SendKey : ArrowAction

        /** Collapse the selection to [position]. */
        data class MoveCursor(val position: Int) : ArrowAction
    }

    /**
     * An arrow moves a collapsed cursor by one code point, never between the halves of a
     * surrogate pair (an emoji), where the next commit would split it; on a selection it
     * collapses to the side it points at without moving further, as it does in desktop editors.
     *
     * [textBefore] and [textAfter] give up to that many units beside the cursor, as
     * `getTextBeforeCursor`/`getTextAfterCursor` do; only the side moved over is asked, and only
     * for a collapsed cursor. Where that tells nothing (null, no text, a lone half) the move is
     * one unit. That cursor must be the one at [start]: [ArrowKeys] keeps it so over a run of
     * presses the editor has not reported yet.
     *
     * The target can be -1 at the very start of the text (or past the end, at the end). The
     * editor ignores an out-of-range selection, so the key simply does nothing there, as it
     * always has.
     */
    fun onArrow(
        traits: EditorTraits,
        direction: Direction,
        start: Int,
        end: Int,
        textBefore: (length: Int) -> CharSequence? = { null },
        textAfter: (length: Int) -> CharSequence? = { null },
    ): ArrowAction {
        if (traits.isRawKeyInput || traits.isUri) return ArrowAction.SendKey
        val offset = if (start == end) codePointUnits(direction, textBefore, textAfter) else 0
        return ArrowAction.MoveCursor(
            when (direction) {
                Direction.Left -> start - offset
                Direction.Right -> end + offset
            }
        )
    }

    private fun codePointUnits(
        direction: Direction,
        textBefore: (Int) -> CharSequence?,
        textAfter: (Int) -> CharSequence?,
    ): Int {
        // two units hold a surrogate pair
        val units = when (direction) {
            Direction.Left -> textBefore(2)?.let { CodePoints.lengthOfLast(it, 1) }
            Direction.Right -> textAfter(2)?.let { CodePoints.lengthOfFirst(it, 1) }
        }
        return units?.takeIf { it > 0 } ?: 1
    }
}
