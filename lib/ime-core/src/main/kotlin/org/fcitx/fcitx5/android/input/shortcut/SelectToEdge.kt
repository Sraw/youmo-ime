/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

/**
 * 选中至文首 / 选中至文尾: the editor's own Shift with |< >|'s key, so the field's edge is the editor's to find, as
 * theirs is (a long field's end lies past any text read). Shift extends from the anchor, which may be either end
 * of a selection: the cursor is first put on the end farther from the edge, so that end is kept.
 */
object SelectToEdge {

    /** Where the cursor goes before the key; null to leave it. */
    fun collapseAt(start: Int, end: Int, toStart: Boolean): Int? = when {
        start == end || start < 0 || end < 0 -> null
        toStart -> maxOf(start, end)
        else -> minOf(start, end)
    }
}
