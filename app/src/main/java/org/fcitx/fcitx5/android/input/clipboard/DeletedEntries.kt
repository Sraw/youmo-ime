/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import org.fcitx.fcitx5.android.input.learning.UndoChip

/**
 * Each entry deleted is only marked till then: 撤销 brings back all deleted while the notice showed; the
 * notice going without it (timed out, tapped away, another in its place) purges them.
 */
internal class DeletedEntries(
    private val undoDelete: (IntArray) -> Unit,
    private val purge: (IntArray) -> Unit,
) {
    private val pending = arrayListOf<Int>()

    // one this window's next took the place of lapses with nothing to purge, its ids the newer one's to undo
    private var last = 0

    fun deleted(vararg ids: Int): Pair<Int, UndoChip.Undo> {
        ids.forEach { pending.add(it) }
        val notice = ++last
        return pending.size to UndoChip.Undo(
            // taken now: a delete made after is another notice's to undo
            undo = { undoDelete(take()) },
            lapsed = { if (notice == last) purge(take()) },
        )
    }

    private fun take(): IntArray = pending.toIntArray().also { pending.clear() }
}
