/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.input.editing.EditorKeyPolicy.ArrowAction
import org.fcitx.fcitx5.android.input.editing.EditorKeyPolicy.Direction
import org.fcitx.fcitx5.android.input.editing.EditorKeyPolicy.ReturnAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What Return and the arrow keys do in each kind of editor. Getting Return wrong is loud: a
 * search box that inserts a newline instead of searching, or a chat box that sends on every
 * line break.
 */
class EditorKeyPolicyTest {

    private val search = 3 // EditorInfo.IME_ACTION_SEARCH
    private val custom = 0x42

    // region Return

    @Test
    fun aPlainMultilineEditorGetsEnter() {
        assertEquals(ReturnAction.SendEnter, EditorKeyPolicy.onReturn(EditorTraits()))
    }

    @Test
    fun anEditorWithAnActionHasItPerformed() {
        assertEquals(
            ReturnAction.PerformEditorAction(search),
            EditorKeyPolicy.onReturn(EditorTraits(imeAction = search)),
        )
    }

    /** A label the editor chose for itself is more specific than the action in imeOptions. */
    @Test
    fun aCustomActionBeatsTheImeAction() {
        assertEquals(
            ReturnAction.PerformEditorAction(custom),
            EditorKeyPolicy.onReturn(EditorTraits(customActionId = custom, imeAction = search)),
        )
    }

    /** IME_FLAG_NO_ENTER_ACTION: the editor wants newlines even though it has an action. */
    @Test
    fun noEnterActionKeepsEnterEvenWithAnAction() {
        assertEquals(
            ReturnAction.SendEnter,
            EditorKeyPolicy.onReturn(
                EditorTraits(noEnterAction = true, customActionId = custom, imeAction = search)
            ),
        )
    }

    @Test
    fun aRawKeyEditorGetsEnterWhateverItAskedFor() {
        assertEquals(
            ReturnAction.SendEnter,
            EditorKeyPolicy.onReturn(EditorTraits(isRawKeyInput = true, imeAction = search)),
        )
    }

    // endregion

    // region arrows

    @Test
    fun anArrowMovesACollapsedCursorByOne() {
        val t = EditorTraits()
        assertEquals(ArrowAction.MoveCursor(3), EditorKeyPolicy.onArrow(t, Direction.Left, 4, 4))
        assertEquals(ArrowAction.MoveCursor(5), EditorKeyPolicy.onArrow(t, Direction.Right, 4, 4))
    }

    /** On a selection an arrow collapses to the side it points at, and goes no further. */
    @Test
    fun anArrowCollapsesASelectionToThatSide() {
        val t = EditorTraits()
        assertEquals(ArrowAction.MoveCursor(2), EditorKeyPolicy.onArrow(t, Direction.Left, 2, 6))
        assertEquals(ArrowAction.MoveCursor(6), EditorKeyPolicy.onArrow(t, Direction.Right, 2, 6))
    }

    /** At the start the target is -1, which the editor ignores. Pinned so a change is deliberate. */
    @Test
    fun leftAtTheStartTargetsMinusOne() {
        assertEquals(
            ArrowAction.MoveCursor(-1),
            EditorKeyPolicy.onArrow(EditorTraits(), Direction.Left, 0, 0),
        )
    }

    private val wave = String(Character.toChars(0x1F44B)) // two UTF-16 units

    /** An arrow at [cursor] in [text], with the editor showing what is beside the cursor. */
    private fun arrow(direction: Direction, text: String, cursor: Int) = EditorKeyPolicy.onArrow(
        EditorTraits(), direction, cursor, cursor,
        textBefore = { text.substring((cursor - it).coerceAtLeast(0), cursor) },
        textAfter = { text.substring(cursor, (cursor + it).coerceAtMost(text.length)) },
    )

    /** One unit beside an emoji is between its surrogates, where the next commit would split it. */
    @Test
    fun anArrowStepsOverAWholeEmoji() {
        val text = "a${wave}b"
        assertEquals(ArrowAction.MoveCursor(1), arrow(Direction.Left, text, 3))
        assertEquals(ArrowAction.MoveCursor(3), arrow(Direction.Right, text, 1))
        // a letter beside one is still one unit
        assertEquals(ArrowAction.MoveCursor(3), arrow(Direction.Left, text, 4))
        assertEquals(ArrowAction.MoveCursor(1), arrow(Direction.Right, text, 0))
    }

    /** A lone half (malformed, or the cursor already between the halves) or no text: one unit. */
    @Test
    fun anArrowMovesOneUnitWhereTheTextTellsNoCodePoint() {
        assertEquals(ArrowAction.MoveCursor(3), arrow(Direction.Right, "a${wave}b", 2))
        assertEquals(ArrowAction.MoveCursor(-1), arrow(Direction.Left, "ab", 0))
    }

    /** Browsers take the arrow key in the address bar to accept the highlighted suggestion. */
    @Test
    fun aUrlFieldGetsTheKeyEvent() {
        assertEquals(
            ArrowAction.SendKey,
            EditorKeyPolicy.onArrow(EditorTraits(isUri = true), Direction.Right, 4, 4),
        )
    }

    @Test
    fun aRawKeyEditorGetsTheKeyEvent() {
        assertEquals(
            ArrowAction.SendKey,
            EditorKeyPolicy.onArrow(EditorTraits(isRawKeyInput = true), Direction.Left, 4, 4),
        )
    }

    // endregion
}
