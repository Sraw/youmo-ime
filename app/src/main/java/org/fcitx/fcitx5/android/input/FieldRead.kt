/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.os.Build
import android.view.inputmethod.InputConnection
import org.fcitx.fcitx5.android.input.shortcut.ClearedText
import org.fcitx.fcitx5.android.input.shortcut.EditPlan

/**
 * The field around the cursor as a run of deletions reads it ([ClearedText.deleting]): one round trip
 * where the editor gives its text around the selection at once ([sdk] 31 and up), its place from the
 * text's offset, or [tracked] (the selection the service follows) where the editor gives none; else a
 * read of each side and the selection. Null where the editor gives neither.
 */
internal fun readAround(
    ic: InputConnection,
    tracked: Int,
    before: Int,
    after: Int,
    selected: Boolean,
    sdk: Int = Build.VERSION.SDK_INT,
): ClearedText.Read? {
    if (sdk >= Build.VERSION_CODES.S) readSurrounding(ic, tracked, before, after)?.let { return it }
    return ClearedText.Read(
        EditPlan.Field(
            ic.getTextBeforeCursor(before, 0)?.toString() ?: return null,
            if (selected) ic.getSelectedText(0)?.toString().orEmpty() else "",
            ic.getTextAfterCursor(after, 0)?.toString() ?: return null,
        ),
        tracked,
    )
}

// the text around the selection in one round trip (API 31): null where the editor gives none, or one
// that does not hold together. Called only behind `sdk >= S`, which lint cannot see through (tests pass
// the level): the suppression on these API 31 calls alone, not over any added to readAround
@SuppressLint("NewApi")
private fun readSurrounding(ic: InputConnection, tracked: Int, before: Int, after: Int): ClearedText.Read? {
    val around = ic.getSurroundingText(before, after, 0) ?: return null
    val start = minOf(around.selectionStart, around.selectionEnd)
    val end = maxOf(around.selectionStart, around.selectionEnd)
    if (start < 0 || end > around.text.length) return null
    val text = around.text.toString()
    val at = if (around.offset >= 0) around.offset + start else tracked
    return ClearedText.Read(EditPlan.Field(text.substring(0, start), text.substring(start, end), text.substring(end)), at)
}
