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

    private var first = false

    fun update(newIndex: Int, newCandidate: CandidateWord, first: Boolean = newIndex == 0) {
        // a holder bound again at another index draws again where that changes its look, not only its candidate
        val moved = this.first != first || ui.bar.labelStyle != null && idx != newIndex
        idx = newIndex
        this.first = first
        if (candidate != newCandidate || moved) {
            candidate = newCandidate
            ui.updateCandidate(newCandidate, newIndex, first)
        }
    }

    fun clear() {
        update(-1, CandidateWord.Empty)
    }

    /** Read on the tap, as the holder may be bound to another candidate before the job runs. */
    fun pick(): suspend CoroutineScope.(HostApi) -> Unit {
        val index = idx
        val text = candidate.text
        return { it.select(index, text) }
    }
}
