/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/** Stops the microphone [tail] samples after the lift: people lift on the last word before its sound is out, and stopping at the lift lost it. */
class VoiceCaptureEnd(
    private val sampleRate: Int,
    /** In frames: the most a reader can be behind what was captured. */
    private val bufferFrames: Int = sampleRate,
) {

    init {
        require(sampleRate > 0) { "sampleRate $sampleRate" }
    }

    val tail = sampleRate * 3 / 10

    // the capture path's latency, at most, that a timestamp may put the lift behind what is read
    private val slack = sampleRate / 5

    /** Null until [lift]. */
    var stopAt: Long? = null
        private set

    var read = 0L
        private set

    /** From its timestamp; null without one: the debug log's. */
    var liftFrame: Long? = null
        private set

    private var liftUptime = 0L

    private var heardAt = Long.MIN_VALUE

    /** [stamp]: where a lift falls and the frames captured, null without a timestamp. The lift counts as seen before this window. */
    fun window(n: Int, now: Long, liftUptime: Long?, stamp: (Long) -> Stamp?): Boolean {
        if (stopAt == null && liftUptime != null) {
            val st = stamp(liftUptime)
            liftFrame = st?.liftFrame
            lift(st?.liftFrame, read, liftUptime, st?.captured ?: read)
        }
        if (n > 0) {
            read += n
            heardAt = now
        }
        return over(read, now)
    }

    /**
     * A timestamp is believed only so far: never before what is read, at most [slack] past the tail beyond what was
     * captured (captured, not read, so a reader behind still gets the tail). One past what the buffer could hold unread
     * is the HAL's error and ignored; a real overrun looks the same and loses the rest of its tail (rare with a 2 s buffer).
     */
    fun lift(liftFrame: Long?, read: Long, at: Long, captured: Long = read) {
        if (stopAt != null) return
        // held to the buffer, the HAL's error still kept the microphone open seconds past the lift
        val believed = liftFrame?.takeIf { captured <= read + bufferFrames }
        val most = maxOf(read, captured) + tail + slack
        stopAt = if (believed == null) read + tail else (believed + tail).coerceIn(read, most)
        liftUptime = at
    }

    /**
     * The first window boundary at or past [stopAt], or [GIVE_UP_MS] with no samples since the lift (else a microphone
     * of empty reads waits for the 15 s timeout); a read blocked in the HAL never gets here, the timeout ends it.
     */
    fun over(read: Long, now: Long): Boolean {
        val stop = stopAt ?: return false
        return read >= stop || now - maxOf(liftUptime, heardAt) >= GIVE_UP_MS
    }

    data class Stamp(val liftFrame: Long, val captured: Long)

    companion object {
        const val GIVE_UP_MS = 1000L

        private const val NS_PER_S = 1_000_000_000L

        /** Both on CLOCK_MONOTONIC. Null for nanoTime 0, which some HALs hand back before the first real one. */
        fun frameAt(framePosition: Long, nanoTime: Long, eventNs: Long, rate: Int): Long? =
            if (nanoTime <= 0) null else framePosition + (eventNs - nanoTime) * rate / NS_PER_S
    }
}
