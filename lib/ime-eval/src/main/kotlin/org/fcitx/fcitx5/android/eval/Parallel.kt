/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors

/** [work] over [items] on up to [threads] threads, each with its own worker, results in order; dealt a card at a time. */
internal fun <T, W, R> dealt(items: List<T>, threads: Int, worker: () -> W, work: W.(List<T>) -> List<R>): List<R> {
    val n = threads.coerceIn(1, maxOf(1, items.size))
    val hands = List(n) { hand -> items.filterIndexed { i, _ -> i % n == hand } }
    fun play(hand: List<T>) = worker().work(hand).also { check(it.size == hand.size) { "${it.size} results for ${hand.size} items" } }
    val results = if (n == 1) {
        listOf(play(hands[0]))
    } else {
        // daemons, as the engine's loops do not stop when interrupted: a failure ends the run without waiting
        val pool = Executors.newFixedThreadPool(n) { task -> Thread(task).apply { isDaemon = true } }
        try {
            val done = ExecutorCompletionService<Pair<Int, List<R>>>(pool)
            hands.forEachIndexed { hand, cards -> done.submit { hand to play(cards) } }
            val byHand = arrayOfNulls<List<R>>(n)
            repeat(n) {
                // the first to fail, as soon as it does
                val (hand, played) = try {
                    done.take().get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
                byHand[hand] = played
            }
            byHand.map { it!! }
        } finally {
            pool.shutdownNow()
        }
    }
    return List(items.size) { i -> results[i % n][i / n] }
}
