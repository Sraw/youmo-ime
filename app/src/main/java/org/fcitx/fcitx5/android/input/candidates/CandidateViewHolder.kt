/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.HostApi

class CandidateViewHolder(val ui: CandidateItemUi) : RecyclerView.ViewHolder(ui.root) {
    var idx = -1
        private set

    var candidate: CandidateWord = CandidateWord.Empty
        private set

    fun update(newIndex: Int, newCandidate: CandidateWord) {
        // a holder bound again at another index draws again where that changes its look (the first's own,
        // its number: the theme's, T6a), not only its candidate
        val moved = ui.byIndex(idx) != ui.byIndex(newIndex)
        idx = newIndex
        if (candidate != newCandidate || moved) {
            candidate = newCandidate
            ui.updateCandidate(newCandidate, newIndex)
        }
    }

    fun clear() {
        update(-1, CandidateWord.Empty)
    }

    /**
     * The pick of the candidate shown now, for a tap: read on the tap, as the holder may be bound
     * to another candidate before the job runs.
     */
    fun pick(): suspend CoroutineScope.(HostApi) -> Unit {
        val index = idx
        val text = candidate.text
        return { it.select(index, text) }
    }
}
