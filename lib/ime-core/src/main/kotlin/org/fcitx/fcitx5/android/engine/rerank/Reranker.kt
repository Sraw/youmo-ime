/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.rerank

import org.fcitx.fcitx5.android.core.CoreLog
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

fun interface SentencePicker {
    /** Index in [readings] to put first, 0 to keep the order; [scores] are the decoder's log10, [context] the text before. */
    fun pick(context: String, readings: List<String>, scores: List<Float>): Int

    /** Reads [context] before the first key (after a commit, on entering a field); the picks are the same either way. */
    fun prewarm(context: String) {}
}

/**
 * Reorders the decoder's best readings by adding a [SentenceModel]'s score (with the text before) to the
 * decoder's; added, not replacing, so the decoder's margin holds where the dictionary has evidence and the
 * model decides where it is thin (简拼, pieced-together sentences). Every reading is scored to the end, so a
 * pick depends on (context, readings, scores) alone. With [ahead], [prewarm] reads on it, so a commit does not
 * wait for that; a pick waits for a read begun and does one not begun itself. There too the chain of the start
 * the context's tail moves to next is read ([SentenceModel.Scorer.readNext]).
 */
class Reranker(
    model: SentenceModel,
    private val weight: Float = WEIGHT,
    private val limit: Int = LIMIT,
    private val ahead: Executor? = null,
) : SentencePicker {

    private val scorer = model.Scorer(readsNext = ahead != null)

    // the scorer is one thread's at a time; sessions on the fcitx thread share it
    private val lock = ReentrantLock()

    // the context last handed to [ahead] and not yet read: a read finding another here leaves it to whoever comes next
    private val asked = AtomicReference<String?>()

    // moved on by each prewarm, never back, so a pick clearing [asked] cannot hide a newer context from a read ahead
    private val asks = AtomicInteger()

    override fun prewarm(context: String) {
        val ahead = ahead ?: return lock.withLock { scorer.prewarm(context) }
        asked.set(context)
        val ask = asks.incrementAndGet()
        ahead.execute { readAhead(context, ask) }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readAhead(context: String, ask: Int) {
        try {
            // a key that read the context first leaves the next chain still to read
            lock.withLock { if (asked.compareAndSet(context, null)) scorer.prewarm(context) }
            // off the lock a pick takes, given up for a newer context, and out of the way of a key being scored
            scorer.readNext(context, stop = { asks.get() != ask }, pause = { if (lock.isLocked) lock.withLock {} })
        } catch (e: Throwable) {
            // off the fcitx thread nothing catches it; the next pick reads the context anew. The class only: the text is typed
            CoreLog.e("context read ahead failed: ${e.javaClass.name}")
        }
    }

    override fun pick(context: String, readings: List<String>, scores: List<Float>): Int =
        if (readings.size < 2) 0 else first(combined(context, readings, scores))

    /** The first [limit] readings' combined scores, decoder's and model's, in nats. */
    internal fun combined(context: String, readings: List<String>, scores: List<Float>): FloatArray {
        val compared = readings.take(limit)
        val model = lock.withLock {
            asked.set(null)
            scorer.score(context, compared, relative = true)
        }
        return FloatArray(compared.size) { scores[it] * LN_10 + weight * model[it] }
    }

    companion object {
        /** The model's log-probability weight against the decoder's, both in nats. */
        const val WEIGHT = 0.4f
        /** How many of the decoder's best readings are compared. */
        const val LIMIT = 5
        // ln 10 rounded to float, written as bits so no libm takes part in a score
        private val LN_10 = Float.fromBits(0x40135d8e)

        /** The first of the highest. */
        internal fun first(combined: FloatArray): Int {
            var best = 0
            var bestScore = Float.NEGATIVE_INFINITY
            for (i in combined.indices) {
                if (combined[i] > bestScore) {
                    best = i
                    bestScore = combined[i]
                }
            }
            return best
        }
    }
}

/** A large model's picker, read when first needed. */
interface LargeHandle {
    /** The picker, read now if not yet (waiting if another thread is reading it); null if it cannot be read. */
    fun get(): SentencePicker?

    /** The picker if already read, without reading or waiting. */
    fun loaded(): SentencePicker?
}

/**
 * [small]'s pick, unless its margin between its first two is under [margin] (and, if [disagreeing], its first is
 * not the decoder's); then the [large] model decides among [small]'s first [sub], kept in the decoder's order.
 * [large] gives null where its model cannot be read: [small]'s pick stands, as at a [Reranker] alone.
 */
class GatedPicker(
    private val small: Reranker,
    private val large: LargeHandle,
    private val margin: Float,
    private val sub: Int,
    private val disagreeing: Boolean,
) : SentencePicker {
    init {
        require(sub >= 1 && !margin.isNaN()) { "sub $sub, margin $margin" }
    }

    /** Keys the gate sent to [large], for the evaluation. */
    var escalations = 0L
        private set

    override fun prewarm(context: String) {
        small.prewarm(context)
        // a model not yet read is not read for this: prewarming saves time only
        large.loaded()?.prewarm(context)
    }

    override fun pick(context: String, readings: List<String>, scores: List<Float>): Int {
        if (readings.size < 2) return 0
        val c = small.combined(context, readings, scores)
        val top = Reranker.first(c)
        var second = Float.NEGATIVE_INFINITY
        for (i in c.indices) if (i != top && c[i] > second) second = c[i]
        if (c[top] - second >= margin || disagreeing && top == 0) return top
        escalations++
        val picker = large.get() ?: return top
        val compared = c.indices.sortedWith(compareByDescending<Int> { c[it] }.thenBy { it }).take(sub).sorted()
        return compared[picker.pick(context, compared.map(readings::get), compared.map(scores::get))]
    }

    companion object {
        /** Full pinyin and 双拼 (grouped too): under this margin in nats, [Reranker.LIMIT]'s first [SUB] go to the large model. */
        val MARGIN: Float = Float.fromBits(0x3f98fb50)
        const val SUB = 2

        /** The nine keys, 14/18 keys and 注音, where the large model helps only against the decoder's first. */
        val NINE_MARGIN: Float = Float.fromBits(0x3dc54800)
        const val NINE_SUB = 5

        /** The gate of a pinyin session at 高档; [nineKeys] for one on a T9Segmenter (九键, 14/18 键, 注音). The evaluation makes it here too, so its numbers are the app's. */
        fun of(small: Reranker, large: LargeHandle, nineKeys: Boolean): GatedPicker =
            if (nineKeys) GatedPicker(small, large, NINE_MARGIN, NINE_SUB, disagreeing = true) else GatedPicker(small, large, MARGIN, SUB, disagreeing = false)
    }
}
