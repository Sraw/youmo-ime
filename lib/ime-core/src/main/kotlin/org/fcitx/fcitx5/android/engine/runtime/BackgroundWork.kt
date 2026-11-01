/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * How many pieces of work handed off the input thread (warm-ups, codings, compactions, the merged
 * dictionary's compiles) are queued or running now: zero is part of the debug build's idle signal,
 * which the device UAT waits for before it reads the candidates, so that a result landing late
 * (a table's coded words, a compile) is not read half-way.
 */
open class BackgroundCount internal constructor() {
    private val running = AtomicInteger()

    val busy: Int get() = running.get()

    /** [work], counted from now till it has run. */
    fun counted(work: Runnable): Runnable = Counted(work, running, null)

    /**
     * [executor], each piece of work it is handed [counted]; one it refuses is not. Only work that
     * never started is handed back: an executor that runs it inline and lets its exception out has
     * already counted it down, and a thread that cannot be started throws an Error, not a
     * RuntimeException, so either way catching less would leave [busy] wrong for good.
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

    /** [work] as an executor is handed it, counted down once it has run: [work] what was handed, for who reads the queue. */
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

/** The process's count, the one the debug build's idle signal reads. */
object BackgroundWork : BackgroundCount()
