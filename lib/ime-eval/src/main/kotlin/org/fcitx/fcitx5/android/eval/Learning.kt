/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.user.UserModel

/**
 * What learning from the user gains, typed through [KeystrokeRun] with the engine learning all
 * along, as it does for a user:
 * - the [Halves.TUNE] half typed once, then again: what a user who types the same things again
 *   sees (their names, their phrases);
 * - the [Halves.HELD_OUT] half, from nothing learned and after the tune half: what carries over
 *   to text not typed before, which learning must not make worse.
 * Each against the same typed by an engine that learns nothing.
 */
class Learning(private val data: PinyinData, private val segmenter: Segmenter) {

    /** Rows for [KeystrokeRun.row]: a label, and the outcomes. */
    fun measure(samples: List<Sample>): List<Pair<String, List<KeystrokeRun.Outcome>>> {
        val tune = Halves.select(samples, Halves.TUNE)
        val heldOut = Halves.select(samples, Halves.HELD_OUT)
        val plain = KeystrokeRun(PinyinSession(data, segmenter))
        val fresh = learner()
        val learner = learner()
        return listOf(
            "tune, learning nothing" to tune.map { plain.type(it) },
            "tune, typed once" to tune.map { learner.type(it) },
            "tune, typed again" to tune.map { learner.type(it) },
            "held-out, learning nothing" to heldOut.map { plain.type(it) },
            "held-out, learning" to heldOut.map { fresh.type(it) },
            "held-out, tune learned" to heldOut.map { learner.type(it) },
        )
    }

    private fun learner() = KeystrokeRun(PinyinSession(data, segmenter, user = UserModel(data.dictionary, data.vocabulary)))
}
