/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.os.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import org.fcitx.fcitx5.android.host.Cancellable
import org.fcitx.fcitx5.android.host.HostClock
import timber.log.Timber
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * The Kotlin host's one thread (fcitx-replacement §4), in place of fcitx's libuv loop: a
 * single-thread scheduled executor still named `fcitx-main`, the coroutine dispatcher every
 * [FcitxAPI] call runs on, and the [HostClock] whose timers (the refine pause, the name overlay)
 * run on the same thread, strictly ordered with the calls around them. Made anew at each [start].
 * [stopWaitSeconds]: how long [stop] waits for a call still running; a test's own.
 */
class HostDispatcher internal constructor(private val stopWaitSeconds: Long) : CoroutineDispatcher(), HostClock {

    constructor() : this(STOP_WAIT_SECONDS)

    @Volatile
    private var executor: ScheduledThreadPoolExecutor? = null

    fun start() {
        check(executor == null) { "already running" }
        executor = ScheduledThreadPoolExecutor(1) { Thread(it, THREAD) }.apply {
            // a cancelled timer leaves the queue at once (a refine cancelled at every key)
            removeOnCancelPolicy = true
        }
    }

    /**
     * Runs [last] on the thread after what is queued before it, then stops; returns the calls that
     * did not get to run (timers not yet due are dropped and not counted, as fcitx's loop drops them
     * on exit). The thread ends even when [last] throws, which is then thrown here.
     */
    fun stop(last: () -> Unit): List<Runnable> {
        val ex = executor ?: return emptyList()
        var rest: List<Runnable> = emptyList()
        try {
            ex.submit(last).get()
        } finally {
            // with dispatch's lock: a call counted is in the queue shutdownNow drains, or never counted
            // (else a dispatch between its read and its execute is rejected after the zeroing: -1, F4306)
            val drained = synchronized(this) {
                executor = null
                ex.shutdownNow()
            }
            // a timer not yet due is no call that was missed (the refine's, the name overlay's)
            rest = drained.filter { !(it is ScheduledFuture<*> && (it.isCancelled || it.getDelay(TimeUnit.MILLISECONDS) > 0)) }
            ex.awaitTermination(stopWaitSeconds, TimeUnit.SECONDS)
            // the calls dropped never run out themselves: only they are taken off, not the count
            // zeroed, as a call still running (one that ignores the interrupt past the wait) counts
            // itself out later (else -1, never idle again); after the wait, so a call the thread
            // had taken when shutdownNow came has left [queued] by then
            jobs.addAndGet(-queued.getAndSet(0))
        }
        return rest
    }

    // the lock with [stop] (F4306) has no deterministic test: one would need a hook between the read
    // of [executor] and the execute; the reasoning is in [stop]
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(this) {
            val ex = executor ?: throw IllegalStateException("Dispatcher is not in running state!")
            jobs.incrementAndGet()
            queued.incrementAndGet()
            try {
                ex.execute {
                    queued.decrementAndGet()
                    try {
                        block.run()
                    } finally {
                        jobs.decrementAndGet()
                    }
                }
            } catch (e: RejectedExecutionException) {
                queued.decrementAndGet()
                jobs.decrementAndGet()
                throw e
            }
        }
    }

    override fun uptimeMillis(): Long = SystemClock.uptimeMillis()

    override fun schedule(delayMillis: Long, task: () -> Unit): Cancellable {
        val ex = executor ?: return Cancellable {}
        val future = ex.schedule(
            {
                // an executor's task would swallow it: said, as a JNI call's throw was
                try {
                    task()
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    Timber.e(e, "host timer")
                }
            },
            delayMillis, TimeUnit.MILLISECONDS,
        )
        return Cancellable { future.cancel(false) }
    }

    companion object {
        private const val THREAD = "fcitx-main"
        private const val STOP_WAIT_SECONDS = 5L

        private val jobs = AtomicInteger()

        // of [jobs], those not yet started: what a stop drops
        private val queued = AtomicInteger()

        /**
         * Calls dispatched to the thread and not yet run out (timers not counted: the refine's has
         * its own flag): the debug build's idle signal (UatIdleProvider) reads it.
         */
        val pendingJobs: Int get() = jobs.get()
    }
}
