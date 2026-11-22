/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.learn.LearnTo
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.user.LearningUse
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.WordPack

/** What learning gains: [Halves.TUNE] typed three times, then [Halves.HELD_OUT], each against an engine that learns nothing. */
class Learning(
    private val data: PinyinData,
    private val segmenter: Segmenter,
    /** Makes an engine's reranker, as the app gives each session its own. */
    private val reranker: (() -> SentencePicker)? = null,
    /** A word pack listed as the user's in every engine, as `pinyin --pack` lists it. */
    private val pack: WordPack? = null,
    /** How what is learned reaches the scores, in every engine. */
    private val use: LearningUse = LearningUse.DEFAULT,
) {

    /** Rows for [KeystrokeRun.row]: a label, and the outcomes. */
    fun measure(samples: List<Sample>): List<Pair<String, List<KeystrokeRun.Outcome>>> {
        val tune = Halves.select(samples, Halves.TUNE)
        val heldOut = Halves.select(samples, Halves.HELD_OUT)
        val alone = Alone()
        val fresh = Learner()
        val tuned = Learner()
        return listOf(
            "tune, learning nothing" to tune.map { alone.type(it) },
            "tune, typed once" to tune.map { tuned.type(it) },
            "tune, typed again" to tune.map { tuned.type(it) },
            "tune, typed a third time" to tune.map { tuned.type(it) },
            "held-out, learning nothing" to heldOut.map { alone.type(it) },
            "held-out, learning" to heldOut.map { fresh.type(it) },
            "held-out, tune learned" to heldOut.map { tuned.type(it) },
        )
    }

    /** An engine learning all along, as the app's. */
    private inner class Learner {
        private val user = EvalUser(data)
        private val keystrokes = KeystrokeRun(session(listed(user.model), user.learnTo(), reranker?.invoke()))

        fun type(sample: Sample) = keystrokes.type(sample)
    }

    /** An engine that learns nothing, with the [pack] and rerankers the learning ones have. */
    private inner class Alone {
        private val user = pack?.let { listed(UserModel(data.dictionary, data.vocabulary, index = data.wordIndex)) }
        private val keystrokes = KeystrokeRun(session(user, LearnTo(), reranker?.invoke()))

        fun type(sample: Sample) = keystrokes.type(sample)
    }

    // no prediction, unlike the app's: KeystrokeRun reads none, and the next sample clears it
    private fun session(user: UserModel?, learnTo: LearnTo, picker: SentencePicker?) =
        PinyinSession(data, segmenter, prediction = false, user = user, use = use, learnTo = learnTo, reranker = picker)

    /** [user] with the [pack]'s words listed. */
    private fun listed(user: UserModel): UserModel {
        val p = pack ?: return user
        p.words.forEach { user.list(it.entry, it.score, 1, data.model.vocabularySize) }
        return user
    }
}
