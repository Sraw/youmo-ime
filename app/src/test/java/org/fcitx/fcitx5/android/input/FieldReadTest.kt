/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.os.Build
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.SurroundingText
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.input.shortcut.EditPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a run of deletions reads of the field before it deletes ([readAround]), which 恢复 puts back:
 * the editor's text around the selection in one trip where it gives it, else each side read apart.
 */
@RunWith(RobolectricTestRunner::class)
class FieldReadTest {

    // an editor giving [around] at once (null: none), and the sides and selection read apart
    private class Editor(
        val around: SurroundingText?,
        val beforeText: String?,
        val selectedText: String?,
        val afterText: String?,
    ) : InputConnectionWrapper(null, false) {
        var surroundingAsked = false

        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? {
            surroundingAsked = true
            return around
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = beforeText
        override fun getSelectedText(flags: Int): CharSequence? = selectedText
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = afterText
    }

    private class Row(
        val why: String,
        val editor: Editor,
        val selected: Boolean,
        val sdk: Int,
        val field: EditPlan.Field?,
        val at: Int,
    )

    private val tracked = 7

    @Test
    fun readsTheFieldAroundTheCursorAsTheEditorGivesIt() {
        assertRows(
            listOf(
                Row(
                    "one trip: the text split at the selection, its place from the editor's offset",
                    Editor(SurroundingText("abcdef", 2, 4, 10), null, null, null), true, Build.VERSION_CODES.S,
                    EditPlan.Field("ab", "cd", "ef"), 12,
                ),
                Row(
                    "one trip, the editor giving no offset: the place the service tracked",
                    Editor(SurroundingText("abc", 3, 3, -1), null, null, null), false, Build.VERSION_CODES.S,
                    EditPlan.Field("abc", "", ""), tracked,
                ),
                Row(
                    "no text around at once: each side read apart, the selection too",
                    Editor(null, "ab", "cd", "ef"), true, Build.VERSION_CODES.S,
                    EditPlan.Field("ab", "cd", "ef"), tracked,
                ),
                Row(
                    "below API 31 the sides alone, nothing selected read as nothing",
                    Editor(SurroundingText("never", 0, 0, 0), "ab", "cd", "ef"), false, Build.VERSION_CODES.R,
                    EditPlan.Field("ab", "", "ef"), tracked,
                ),
                Row(
                    "a side the editor will not give: nothing read, nothing kept",
                    Editor(null, null, "", "ef"), false, Build.VERSION_CODES.S,
                    null, tracked,
                ),
            ),
            { it.why },
        ) { row ->
            val read = readAround(row.editor, tracked, 64, 64, row.selected, row.sdk)
            if (row.field == null) {
                assertNull(read)
            } else {
                assertEquals(row.field, read?.field)
                assertEquals(row.at, read?.at)
            }
            if (row.sdk < Build.VERSION_CODES.S) assertEquals(false, row.editor.surroundingAsked)
        }
    }
}
