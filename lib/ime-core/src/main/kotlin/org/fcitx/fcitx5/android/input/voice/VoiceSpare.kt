/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** One thing made ahead (the next opening's VAD), handed over lock-free: waiting on the engine's load lock kept 准备中 up for seconds. */
class VoiceSpare<T : Any>(private val release: (T) -> Unit) {

    private val spare = AtomicReference<T?>(null)
    private val building = AtomicBoolean(false)

    // counts [clear]s: one made across a clear is let go, not kept to hold its memory to the next
    private val cleared = AtomicInteger(0)

    // set by tests alone, to land a clear between a build's keep and its undo rather than hope a race does
    internal var afterOffer: () -> Unit = {}

    /** None if one is there or on its way, so repeated shows start no second build. Whether this call made it. */
    fun build(make: () -> T): Boolean {
        if (spare.get() != null || !building.compareAndSet(false, true)) return false
        try {
            val at = cleared.get()
            val made = make()
            val kept = offer(made)
            afterOffer()
            // a clear during the build or before the offer found nothing to let go: undo the keep (if one after let [made] go, the swap fails)
            if (kept && cleared.get() != at && spare.compareAndSet(made, null)) {
                release(made)
                return false
            }
            return kept
        } finally {
            building.set(false)
        }
    }

    /** A failure, an Error too (native library, memory), goes to [failed] rather than ending the process; cancellation still thrown. */
    @Suppress("TooGenericExceptionCaught")
    fun tryBuild(make: () -> T, failed: (Throwable) -> Unit): Boolean = try {
        build(make)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        failed(e)
        false
    }

    /** Null if none. */
    fun take(): T? = spare.getAndSet(null)

    /** Read before deciding an [offer]'s `wanted`, and given to it. */
    val clears: Int get() = cleared.get()

    /** Released if one is already there. A clear since [clearsBefore] (the decision stale) undoes the keep. */
    fun offer(made: T, wanted: Boolean = true, clearsBefore: Int? = null): Boolean {
        // not wanted: nothing holds the models (a talk whose models failed), so nothing would let it go
        if (wanted && spare.compareAndSet(null, made)) {
            if (clearsBefore != null && cleared.get() != clearsBefore && spare.compareAndSet(made, null)) {
                release(made)
                return false
            }
            return true
        }
        release(made)
        return false
    }

    /** Memory back while voice is not used. */
    fun clear() {
        cleared.incrementAndGet()
        spare.getAndSet(null)?.let(release)
    }
}
