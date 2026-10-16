/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner

/**
 * The [refiner]'s weighing of one order shown ([T]: the session's), in slices while the user pauses
 * ([Action.Refine]), as pinyin spends them: [PinyinSession.REFINE_BUDGET] a slice, given up after
 * [SentenceRefiner.slices]. For handwriting and the tables, which kept a copy each.
 */
class RefineSlices<T : Any>(private val refiner: SentenceRefiner?) {
    private var pending: T? = null
    private var spent = 0

    /** Whether there is something left to weigh: the snapshot's refines. */
    val refines: Boolean get() = pending != null

    /** [order], of [readings] sentences, weighed from its first slice; one alone, or no refiner, has nothing to weigh. */
    fun start(order: T?, readings: Int) {
        spent = 0
        pending = order.takeIf { refiner != null && readings > 1 }
    }

    /** What was shown changed: nothing left to weigh. */
    fun drop() {
        pending = null
    }

    /**
     * One slice. Once the refiner picks, [place] puts its pick first (none if it has nothing to
     * say); till then (null) the next slice goes on from its work.
     */
    fun slice(context: String, texts: (T) -> List<String>, scores: (T) -> List<Float>, place: (T, Int) -> Unit) {
        val order = pending ?: return
        val r = refiner
        if (r == null || spent++ >= r.slices) {
            pending = null
            return
        }
        val picked = r.refine(context, texts(order), scores(order), PinyinSession.REFINE_BUDGET) ?: return
        pending = null
        if (picked != SentenceRefiner.NONE) place(order, picked)
    }
}
