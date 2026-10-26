/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * One thing made ahead for the next use (the VAD for the next microphone opening), handed over
 * without a lock: the capture thread takes it while the models may be loading under the engine's
 * lock, and waiting on that kept 准备中 up for the whole load (4 s and more) with the microphone on.
 * [release] lets go of one not kept.
 */
class VoiceSpare<T : Any>(private val release: (T) -> Unit) {

    private val spare = AtomicReference<T?>(null)
    private val building = AtomicBoolean(false)

    // counts [clear]s: one made across a clear is let go, not kept to hold its memory to the next
    private val cleared = AtomicInteger(0)

    // the step between a build's keep and its undo, where a clear on another thread can land; set by
    // tests alone, to put that clear there rather than hope a race does
    internal var afterOffer: () -> Unit = {}

    /**
     * One made with [make] if none is there or on its way: the keyboard shown again and again
     * while one builds starts no second build beside it. Whether this call made it.
     */
    fun build(make: () -> T): Boolean {
        if (spare.get() != null || !building.compareAndSet(false, true)) return false
        try {
            val at = cleared.get()
            val made = make()
            val kept = offer(made)
            afterOffer()
            // a clear during the build or before the offer found nothing to let go: undo the keep (one
            // after it let go of [made] itself, and the swap fails)
            if (kept && cleared.get() != at && spare.compareAndSet(made, null)) {
                release(made)
                return false
            }
            return kept
        } finally {
            building.set(false)
        }
    }

    /** the one made ahead, now no one else's; null if none */
    fun take(): T? = spare.getAndSet(null)

    val ready get() = spare.get() != null

    /** [made] kept for the next use; released if one is already there. Whether it was kept */
    fun offer(made: T): Boolean {
        if (spare.compareAndSet(null, made)) return true
        release(made)
        return false
    }

    /** the one made ahead let go: memory back while voice is not used */
    fun clear() {
        cleared.incrementAndGet()
        spare.getAndSet(null)?.let(release)
    }
}
