/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.device

import org.fcitx.fcitx5.android.core.CoreLog

/** The calling thread's CPU mask. */
interface Affinity {
    /** 0, or -1 when refused (EINVAL: none of [mask] in the cpuset or online). */
    fun set(mask: Long): Long
}

/**
 * Back on every [possible] core, which the kernel cuts to the cpuset; not a mask read before, which lacks the cores
 * offline or isolated then and from Linux 6.2 outlives a cpuset change. Where [possible] is refused, every bit;
 * false then.
 */
fun Affinity.release(possible: Long): Boolean {
    if (set(possible) >= 0) return true
    set(-1L)
    return false
}

/**
 * Pins a stretch to [bigMask] and puts the thread back on all [possible] cores after, once for nested stretches.
 * Where pinning is refused it is not tried again for [BACKOFF_NANOS]: it changes speed only, never a result.
 */
class BigCorePin(
    private val affinity: Affinity,
    private val bigMask: Long,
    private val possible: Long,
    private val clock: () -> Long = System::nanoTime,
) : CorePin {

    private class Stretch {
        var depth = 0
        var pinned = false
    }

    // affinity is a thread's own
    private val stretch = ThreadLocal.withInitial(::Stretch)

    @Volatile
    private var refusedAt: Long? = null

    @Volatile
    private var told = false

    @Volatile
    private var toldRelease = false

    override fun <T> run(block: () -> T): T {
        if (bigMask == 0L || !enabled) return block()
        val s = stretch.get()
        try {
            if (s.depth++ == 0) enter(s)
            return block()
        } finally {
            if (--s.depth == 0 && s.pinned) {
                s.pinned = false
                if (!affinity.release(possible) && !toldRelease) {
                    toldRelease = true
                    CoreLog.d { "cores 0x${possible.toString(16)} refused after a pinned stretch: every core asked for instead" }
                }
            }
        }
    }

    private fun enter(s: Stretch) {
        val now = clock()
        val at = refusedAt
        if (at != null && now - at < BACKOFF_NANOS) return
        if (affinity.set(bigMask) < 0) {
            refusedAt = now
            if (!told) {
                told = true
                CoreLog.d { "big cores 0x${bigMask.toString(16)} refused: typing runs unpinned, tried again in 10 s" }
            }
        } else {
            refusedAt = null
            s.pinned = true
        }
    }

    companion object {
        const val BACKOFF_NANOS = 10_000_000_000L

        /** Off for a device run that measures pinned against not. */
        @Volatile
        var enabled = true
    }
}
