/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Off-input-thread work queued or running; zero is part of the debug build's idle signal, which device UAT waits for so late results are not read half-way. */
open class BackgroundCount internal constructor() {
    private val running = AtomicInteger()

    val busy: Int get() = running.get()

    /** [work], counted from now till it has run. */
    fun counted(work: Runnable): Runnable = Counted(work, running, null)

    /**
     * [executor] with each task [counted]; one it refuses is uncounted. Only never-started work is handed back:
     * an inline executor has already counted down, and a thread that cannot start throws an Error, so catching
     * less would leave [busy] wrong for good.
     */
    fun counted(executor: Executor) = Executor { work ->
        val started = AtomicBoolean()
        try {
            executor.execute(Counted(work, running, started))
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            if (!started.get()) running.decrementAndGet()
            throw e
        }
    }

    /** Counted down once run; [work] kept for whoever reads the queue. */
    internal class Counted(val work: Runnable, private val running: AtomicInteger, private val started: AtomicBoolean?) : Runnable {
        init {
            running.incrementAndGet()
        }

        override fun run() {
            try {
                started?.set(true)
                work.run()
            } finally {
                running.decrementAndGet()
            }
        }
    }
}

/** The process's count, read by the debug build's idle signal. */
object BackgroundWork : BackgroundCount()
