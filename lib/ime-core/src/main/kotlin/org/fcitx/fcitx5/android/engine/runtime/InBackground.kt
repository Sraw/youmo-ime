/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import java.util.concurrent.Executor

/**
 * A value made on [executor] per key, null till made; asking under a new key drops the old key's value even
 * if it arrives late. A throwing make (Errors too) goes to [onError] and stays unmade for its key: on a bare
 * thread it would kill the input method (an OOM coding a merged dictionary once crash-looped it).
 */
internal class InBackground<V : Any>(private val executor: Executor, private val onError: (Throwable) -> Unit) {
    private val lock = Any()
    private var asked: Any? = null
    private var made: V? = null

    // one per ask: a key asked again later is a new ask, which an earlier make must not fill
    private var asks = 0L

    /** Null while being made. [snapshot] runs once per key on the caller's thread and returns the work, which runs on [executor] reading nothing the caller changes; it may stop early once no longer asked, and its result is then dropped. */
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
                // whatever it throws: uncaught on a bare thread it would kill the input method
                onError(e)
                return@execute
            }
            synchronized(lock) { if (asks == ask) made = value }
        }
        return synchronized(lock) { made.takeIf { asks == ask } }
    }
}
