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

/** The host's one thread (`fcitx-main`): every [HostApi] call and every [HostClock] timer, strictly ordered. Made anew at each [start]. */
class HostDispatcher internal constructor(private val stopWaitSeconds: Long) : CoroutineDispatcher(), HostClock {

    constructor() : this(STOP_WAIT_SECONDS)

    @Volatile
    private var executor: ScheduledThreadPoolExecutor? = null

    fun start() {
        check(executor == null) { "already running" }
        executor = ScheduledThreadPoolExecutor(1) { Thread(it, THREAD) }.apply {
            // a refine timer is cancelled at every key
            removeOnCancelPolicy = true
        }
    }

    /** Runs [last] after what is queued, then stops; returns the calls that never ran (pending timers dropped, as fcitx's loop does). */
    fun stop(last: () -> Unit): List<Runnable> {
        val ex = executor ?: return emptyList()
        var rest: List<Runnable> = emptyList()
        try {
            ex.submit(last).get()
        } finally {
            // under dispatch's lock: a call counted is in the queue shutdownNow drains, or never counted
            val drained = synchronized(this) {
                executor = null
                ex.shutdownNow()
            }
            rest = drained.filter { !(it is ScheduledFuture<*> && (it.isCancelled || it.getDelay(TimeUnit.MILLISECONDS) > 0)) }
            ex.awaitTermination(stopWaitSeconds, TimeUnit.SECONDS)
            // subtract the dropped calls rather than zeroing: one still running past the wait counts itself out later
            jobs.addAndGet(-queued.getAndSet(0))
        }
        return rest
    }

    // the lock with [stop] has no deterministic test (it would need a hook between reading [executor] and execute)
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
                // an executor's task would swallow it
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

        private val queued = AtomicInteger()

        /** Timers not counted; the debug idle signal (UatIdleProvider) reads it. */
        val pendingJobs: Int get() = jobs.get()
    }
}
