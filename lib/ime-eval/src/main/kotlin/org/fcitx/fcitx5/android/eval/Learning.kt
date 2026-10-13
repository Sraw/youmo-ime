/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.LayerPrior
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.WordPack

/**
 * What learning from the user gains, typed through [KeystrokeRun] with the engine learning all
 * along, as it does for a user:
 * - the [Halves.TUNE] half typed once, then again: what a user who types the same things again
 *   sees (their names, their phrases);
 * - the [Halves.HELD_OUT] half, from nothing learned and after the tune half: what carries over
 *   to text not typed before, which learning must not make worse.
 * Each against the same typed by an engine that learns nothing.
 *
 * Each engine is a session as the app makes one: with rerankers of its own when given.
 */
class Learning(
    private val data: PinyinData,
    private val segmenter: Segmenter,
    /** Makes an engine's reranker, as the app gives each session its own. */
    private val reranker: (() -> SentencePicker)? = null,
    /** Makes an engine's refiner, which weighs the readings again while the user pauses. */
    private val refiner: (() -> SentenceRefiner)? = null,
    /** A word pack listed as the user's in every engine, as `pinyin --pack` lists it. */
    private val pack: WordPack? = null,
) {

    /** Rows for [KeystrokeRun.row]: a label, and the outcomes; a note (where the prior ended up) has none, null. */
    fun measure(samples: List<Sample>): List<Pair<String, List<KeystrokeRun.Outcome>?>> {
        val tune = Halves.select(samples, Halves.TUNE)
        val heldOut = Halves.select(samples, Halves.HELD_OUT)
        val alone = Alone()
        val fresh = Learner()
        val tuned = Learner()
        val rows = mutableListOf<Pair<String, List<KeystrokeRun.Outcome>?>>(
            "tune, learning nothing" to tune.map { alone.type(it) },
            "tune, typed once" to tune.map { tuned.type(it) },
            "tune, typed again" to tune.map { tuned.type(it) },
            "held-out, learning nothing" to heldOut.map { alone.type(it) },
            "held-out, learning" to heldOut.map { fresh.type(it) },
            "held-out, tune learned" to heldOut.map { tuned.type(it) },
        )
        // with layers, the same again learning the prior per layer too, and where it ended up
        if (data.layers.count > 1) {
            val freshWithPrior = Learner(learnsPrior = true)
            val tunedWithPrior = Learner(learnsPrior = true)
            rows += "tune, typed once, prior learned" to tune.map { tunedWithPrior.type(it) }
            val afterTune = tunedWithPrior.prior.toString()
            rows += "held-out, learning, prior learned" to heldOut.map { freshWithPrior.type(it) }
            rows += "held-out, tune learned, prior learned" to heldOut.map { tunedWithPrior.type(it) }
            rows += "prior after tune: ${afterTune.ifEmpty { "0" }}; after held-out alone: ${freshWithPrior.prior.toString().ifEmpty { "0" }}" to null
        }
        return rows
    }

    /** An engine learning all along, as the app's: the user's words and, if [learnsPrior], the prior per layer. */
    private inner class Learner(learnsPrior: Boolean = false) {
        private val user = UserModel(data.dictionary, data.vocabulary)
        val prior = if (learnsPrior) LayerPrior(data.layers, extra = { id -> user.layerOf(id) }) else null
        private val keystrokes = KeystrokeRun(
            session(listed(user, prior), prior, reranker?.invoke(), refiner?.invoke()),
        )

        fun type(sample: Sample) = keystrokes.type(sample)
    }

    /** An engine that learns nothing, with the [pack] and rerankers the learning ones have. */
    private inner class Alone {
        private val learned = HashSet<UserModel.Entry>()
        // the pack listed once, not again for every sample: what the session commits, the model
        // learns, and forgets after the sample
        private val user = pack?.let { listed(UserModel(data.dictionary, data.vocabulary), null) }?.apply {
            journal = UserModel.Journal { _, sentence -> learned += sentence }
        }
        private val keystrokes = KeystrokeRun(session(user, null, reranker?.invoke(), refiner?.invoke()))

        fun type(sample: Sample) = keystrokes.type(sample).also {
            user?.forgetEach(learned)
            learned.clear()
        }
    }

    // no prediction, unlike the app's: KeystrokeRun reads none, and the next sample clears it
    private fun session(user: UserModel?, prior: LayerPrior?, picker: SentencePicker?, refine: SentenceRefiner?) =
        PinyinSession(data, segmenter, prediction = false, user = user, prior = prior, refiner = refine, reranker = picker)

    /** [user] with the [pack]'s words listed, in the pack's layer of [prior] if there is one. */
    private fun listed(user: UserModel, prior: LayerPrior?): UserModel {
        val p = pack ?: return user
        val layer = prior?.layer(p.layer) ?: 1
        p.words.forEach { user.list(it.entry, it.score, layer, data.model.vocabularySize) }
        return user
    }
}
