/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * Where the microphone stops once the finger lifts to type: [tail] samples after the lift, counted
 * in samples, not time. People lift on the last word, a little before its sound is out: stopped at
 * the lift, the last character was lost (57.7% when 300 ms early, 10.7% right at the end; with the
 * tail, lifting 300 ms early loses it no more often than lifting at the end did). Fed from the
 * capture thread a window at a time ([window]).
 */
class VoiceCaptureEnd(
    private val sampleRate: Int,
    /** the record's buffer, in frames: the most a reader can be behind what was captured */
    private val bufferFrames: Int = sampleRate,
) {

    init {
        require(sampleRate > 0) { "sampleRate $sampleRate" }
    }

    /** samples recorded after the lift */
    val tail = sampleRate * 3 / 10

    // the capture path's latency, at most, that a timestamp may put the lift behind what is read
    private val slack = sampleRate / 5

    /** the samples read at which it stops; null until [lift] */
    var stopAt: Long? = null
        private set

    /** samples read so far, as [window] counts them */
    var read = 0L
        private set

    /** where the lift fell in the record, from its timestamp; null without one: the debug log's */
    var liftFrame: Long? = null
        private set

    private var liftUptime = 0L

    // uptime of the last window with samples in it
    private var heardAt = Long.MIN_VALUE

    /**
     * One read of [n] samples at uptime [now] (0: none came), [liftUptime] the lift's uptime once the
     * finger is up, [stamp] where a lift at that uptime falls in the record and how many frames
     * the record had captured (from its timestamp; null without one). Whether to stop now.
     *
     * The lift is taken as seen before this window is counted: the window being read as the finger
     * lifted is not what was read before it.
     */
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
     * The lift, seen when [read] samples are in: [liftFrame] where it fell in the record ([frameAt];
     * null without a timestamp, then counted from [read]), [at] its uptime in ms, [captured] the
     * frames the record had taken in by then (a timestamp's framePosition). A timestamp is believed
     * only so far: never before what is read, at most [slack] after the tail past what was
     * captured. Captured, not read: a reader running behind still gets all up to the lift's tail.
     * A timestamp past what the buffer could hold unread is not believed at all: counted from [read].
     * A real overrun (the reader more than a buffer behind) looks the same, and then what the buffer
     * still held past the tail is lost; taken, as with a 2 s buffer it is rare and the HAL's error
     * kept the microphone open seconds past the lift.
     */
    fun lift(liftFrame: Long?, read: Long, at: Long, captured: Long = read) {
        if (stopAt != null) return
        // a framePosition past what the buffer could hold unread is the HAL's error: held to the
        // buffer it still kept the microphone open some 2.5 s past the lift with a 2 s buffer
        val believed = liftFrame?.takeIf { captured <= read + bufferFrames }
        val most = maxOf(read, captured) + tail + slack
        stopAt = if (believed == null) read + tail else (believed + tail).coerceIn(read, most)
        liftUptime = at
    }

    /**
     * Whether to stop with [read] samples in, at uptime [now] ms: the first window boundary at or
     * past [stopAt]. Or after [GIVE_UP_MS] with no samples since the lift (or since the last that
     * came after it): a microphone whose reads come back empty would otherwise leave the sentence to
     * the 15 s timeout. Only then: a reader running behind still gets the tail it has yet to read.
     * Seen only as reads return: a read blocked in the HAL never gets here, and nothing else ends
     * it either; the session's 15 s timeout does.
     */
    fun over(read: Long, now: Long): Boolean {
        val stop = stopAt ?: return false
        return read >= stop || now - maxOf(liftUptime, heardAt) >= GIVE_UP_MS
    }

    /** A record's timestamp put to a lift: where it fell, and the frames captured at the timestamp. */
    data class Stamp(val liftFrame: Long, val captured: Long)

    companion object {
        const val GIVE_UP_MS = 1000L

        private const val NS_PER_S = 1_000_000_000L

        /**
         * The record's frame at [eventNs], from a timestamp of it ([framePosition] at [nanoTime]),
         * both on CLOCK_MONOTONIC: what [lift] takes. Null for a timestamp of no time: some HALs
         * hand back nanoTime 0 before the first real one.
         */
        fun frameAt(framePosition: Long, nanoTime: Long, eventNs: Long, rate: Int): Long? =
            if (nanoTime <= 0) null else framePosition + (eventNs - nanoTime) * rate / NS_PER_S
    }
}
