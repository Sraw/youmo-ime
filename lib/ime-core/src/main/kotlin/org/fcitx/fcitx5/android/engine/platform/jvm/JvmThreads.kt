/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.platform.jvm

import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object JvmThreads {
    private const val IDLE_SECONDS = 30L

    /** A fresh daemon thread per task: none kept, so engines closed and remade still have one. */
    fun each(name: String) = Executor { Thread(it, name).apply { isDaemon = true }.start() }

    /** One daemon thread, tasks in order: kept while busy so what it allocates per thread is reused, gone once idle. */
    fun single(name: String): Executor = ThreadPoolExecutor(1, 1, IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) {
        Thread(it, name).apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }
}
