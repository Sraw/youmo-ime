/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

/** The `EditorInfo` bits that decide Backspace, Return and arrows, decoded on the Android side so the decisions test without an editor. */
data class EditorTraits(
    /** `TYPE_NULL` (a terminal, a game): keys must go as raw key events. */
    val isRawKeyInput: Boolean = false,
    /** Browsers use the arrows there to accept an address-bar suggestion. */
    val isUri: Boolean = false,
    val isEmail: Boolean = false,
    /** Characters hidden or shown (a shown one is only sensitive to fcitx). */
    val isPassword: Boolean = false,
    val isNumber: Boolean = false,
    /** Null for any other field. */
    val number: NumberKind? = null,
    val isSearch: Boolean = false,
    /** Not read, not even to step over an emoji, but by the address bar (≤256 units either side, nothing kept). */
    val isSensitive: Boolean = false,
    /** Opted in via `privateImeOptions` to Backspace as an edit; only our own key-binding preference does. */
    val acceptsDeleteSurrounding: Boolean = false,
    val noEnterAction: Boolean = false,
    val customActionId: Int? = null,
    /** Null when `UNSPECIFIED` or `NONE`. */
    val imeAction: Int? = null,
    /** `SEND`, in `imeOptions` or its own labelled action. */
    val sends: Boolean = false,
    val isMultiLine: Boolean = false,
    /** `TYPE_TEXT_FLAG_MULTI_LINE` alone: the field shows lines (the space's slide moves among them). */
    val showsLines: Boolean = false,
    val isShortMessage: Boolean = false,
)

/** Not whether a number is signed or decimal, nor a PIN: what a field declares is often not what it takes. */
enum class NumberKind {
    NUMBER,
    PHONE,

    /** A date, a time or both. */
    DATETIME,
}

object EditorKeyPolicy {

    sealed interface ReturnAction {
        data object SendEnter : ReturnAction

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
        data object SendKey : ArrowAction

        data class MoveCursor(val position: Int) : ArrowAction
    }

    /**
     * An arrow moves a collapsed cursor one code point, never splitting a surrogate pair; on a selection it collapses
     * to that side. Where the text tells nothing the move is one unit. [start] must be the cursor [ArrowKeys] tracks;
     * -1 or past the end is ignored by the editor.
     */
    fun onArrow(
        traits: EditorTraits,
        direction: Direction,
        start: Int,
        end: Int,
        textBefore: (length: Int) -> CharSequence? = { null },
        textAfter: (length: Int) -> CharSequence? = { null },
    ): ArrowAction {
        if (traits.isSensitive) return ArrowAction.SendKey
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
