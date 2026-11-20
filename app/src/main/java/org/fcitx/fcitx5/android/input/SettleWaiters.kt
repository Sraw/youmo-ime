/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.os.Handler
import android.os.Looper

/** Each also posted to run at its time-out. */
class SettleWaiters(private val settled: () -> Boolean) {

    private val waiters = ArrayList<Runnable>()
    private val timeouts by lazy { Handler(Looper.getMainLooper()) }

    fun add(timeoutMs: Long, block: () -> Unit) {
        if (settled()) return block()
        val waiter = object : Runnable {
            override fun run() {
                if (waiters.remove(this)) block()
            }
        }
        waiters += waiter
        timeouts.postDelayed(waiter, timeoutMs)
    }

    /** None run later, nor the service held by the main looper. */
    fun drop() {
        if (waiters.isEmpty()) return
        timeouts.removeCallbacksAndMessages(null)
        waiters.clear()
    }

    fun wake() {
        if (waiters.isEmpty() || !settled()) return
        for (waiter in waiters.toList()) {
            timeouts.removeCallbacks(waiter)
            waiter.run()
        }
    }
}
