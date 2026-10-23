/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import java.util.concurrent.Executor

/**
 * A value made on [executor], off the input thread, for the [get]s that ask for it by a key: null
 * till it is made. Asked for under another key (what it is made of changed), the one made for an
 * older key is dropped, however late it comes, and so is one being made. A make that throws is
 * reported to [onError] and stays unmade for its key (null, not retried at every [get]): it runs
 * on a bare thread, where an uncaught exception would kill the input method. An Error too: an
 * OutOfMemoryError coding a merged dictionary for a table (0.3.1) killed it so, crash-looping.
 */
internal class InBackground<V : Any>(private val executor: Executor, private val onError: (Throwable) -> Unit) {
    private val lock = Any()
    private var asked: Any? = null
    private var made: V? = null

    // one per ask: a key asked for again later is a new ask, which a make of the earlier one (perhaps stopped early) must not fill
    private var asks = 0L

    /**
     * The value for [key], or null while it is being made. [snapshot] runs here, on the caller's
     * thread, once per key: it reads what the value is made of and returns the work, which runs on
     * [executor] and reads nothing the caller's thread changes. The work may stop early once its
     * argument says the key is no longer asked for; what it returns then is dropped.
     */
    fun get(key: Any, snapshot: () -> (stillAsked: () -> Boolean) -> V): V? {
        val ask = synchronized(lock) {
            if (asked == key) return made
            asked = key
            made = null
            ++asks
        }
        val work = snapshot()
        val stillAsked = { synchronized(lock) { asks == ask } }
        executor.execute {
            if (!stillAsked()) return@execute
            val value = try {
                work(stillAsked)
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                // whatever it throws: on a bare thread, uncaught, it would kill the input method
                onError(e)
                return@execute
            }
            synchronized(lock) { if (asks == ask) made = value }
        }
        return synchronized(lock) { made.takeIf { asks == ask } }
    }
}
