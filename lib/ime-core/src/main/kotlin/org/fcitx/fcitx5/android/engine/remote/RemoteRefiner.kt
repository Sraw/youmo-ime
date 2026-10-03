/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.remote

import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Weighs the decoder's best readings by a [RemoteModel] while the user pauses, after the [local]
 * refiner has had its say. The question goes out once [local] has picked: by then it is a pause,
 * not a key on its way to the next (asked at the first slice, a question for each key queued on
 * the server behind the one before, and the pause's own came too late). Then each slice waits up
 * to [wait] for the answer. With no answer [timeout] after it was asked (the server off, or the
 * phone off the network) the order is [local]'s, as if there were no server; a question that got
 * none is asked again if the same input comes back.
 *
 * The pick is the reading with the best log P from the server less [penalty] for each place it
 * is down [local]'s order, among the first [limit]: how the 0.8B and 9B were measured (training
 * plan, section 6), where the decoder's order alone kept too many slips the model liked.
 * Times are in nanoseconds, as [clock] gives them.
 */
class RemoteRefiner(
    private val local: SentenceRefiner?,
    private val remote: RemoteModel,
    private val timeout: Long = TIMEOUT,
    private val wait: Long = WAIT,
    private val penalty: Float = PENALTY,
    private val limit: Int = LIMIT,
    private val clock: () -> Long = System::nanoTime,
) : SentenceRefiner {

    private class Question(val context: String, val readings: List<String>) {
        var answer: Future<FloatArray>? = null
        var asked = 0L
        var failed = false
        var localSlices = 0
        var localDone = false
        var localPick = SentenceRefiner.NONE
    }

    private var question: Question? = null

    // the local refiner's own, and enough more to wait out the timeout a slice at a time
    override val slices: Int get() = (local?.slices ?: 0) + (timeout / maxOf(wait, 1)).toInt() + 1

    override fun offline(): SentenceRefiner? = local

    override fun refine(context: String, readings: List<String>, scores: List<Float>, budget: Int): Int? {
        if (readings.size < 2) return if (local == null) SentenceRefiner.NONE else local.refine(context, readings, scores, budget)
        val compared = readings.take(limit)
        val q = question?.takeIf { it.context == context && it.readings == compared && !it.failed }
            ?: Question(context, compared).also {
                // not interrupted: one being sent finishes and keeps its connection; one waiting is never sent
                question?.answer?.cancel(false)
                question = it
            }
        if (!q.localDone) {
            // given no more slices than it would have alone: past them it has nothing to say
            val more = local != null && q.localSlices++ < local.slices
            q.localPick = if (more) local.refine(context, readings, scores, budget) ?: return null else SentenceRefiner.NONE
            q.localDone = true
        }
        // timed from here: the server's time is its own, not what the local refiner left of it
        val answer = q.answer ?: remote.score(context, compared).also {
            q.answer = it
            q.asked = clock()
        }
        val left = q.asked + timeout - clock()
        val scored = try {
            if (answer.isDone) answer.get() else answer.get(minOf(maxOf(left, 0), wait), TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            if (left > wait) return null
            answer.cancel(false)
            return q.giveUp()
        } catch (_: ExecutionException) {
            return q.giveUp()
        } catch (_: CancellationException) {
            return q.giveUp()
        }
        return pick(q.localPick, scored)
    }

    private fun Question.giveUp(): Int {
        failed = true
        return localPick
    }

    /**
     * The index in the readings of the best by the server's [scored], [localPick] taken as first;
     * none, if [local] picked none and the server's best is the decoder's first: what is shown
     * then (the smaller model's pick, say) stays, as it would with no server.
     */
    private fun pick(localPick: Int, scored: FloatArray): Int {
        // a pick past those the server saw is kept: there is nothing to weigh it against
        if (localPick != SentenceRefiner.NONE && localPick !in scored.indices) return localPick
        val first = if (localPick in scored.indices) localPick else 0
        val order = listOf(first) + scored.indices.filter { it != first }
        var best = first
        var bestScore = Float.NEGATIVE_INFINITY
        order.forEachIndexed { place, i ->
            val s = scored[i] - penalty * place
            if (s > bestScore) {
                best = i
                bestScore = s
            }
        }
        return if (best == first && localPick == SentenceRefiner.NONE) SentenceRefiner.NONE else best
    }

    companion object {
        /** What a pause is worth waiting for: past it the user is reaching for a candidate. */
        val TIMEOUT = TimeUnit.MILLISECONDS.toNanos(300)
        /** A slice's wait, which a key pressed meanwhile waits out too. */
        val WAIT = TimeUnit.MILLISECONDS.toNanos(10)
        const val PENALTY = 1f
        const val LIMIT = 5
    }
}
