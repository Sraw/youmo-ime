/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * The order of a microphone opening: recording [start]ed first, the speech detector [load]ed only
 * then (its model read in after the microphone is on, not before: the press's first words are in
 * the record's buffer meanwhile, dev/design/voice-start-latency.md). A detector that fails to load
 * is none ([use] gets null, VoiceQuiet then keeps everything): Chinese and the microphone go on
 * without it. [stop] and [unload] whatever happens, the record let go even if the detector broke.
 */
object VoiceOpening {
    @Suppress("TooGenericExceptionCaught")
    // try/finally, not crossinline: [use] suspends at the caller (VoiceListener), and a return out of a
    // lambda still runs the stop and the unload
    inline fun <V : Any> run(
        start: () -> Unit,
        load: () -> V,
        use: (V?) -> Unit,
        noinline stop: () -> Unit,
        noinline unload: (V) -> Unit,
        noinline failed: (Throwable) -> Unit = {},
    ) {
        var detector: V? = null
        // what broke first is what is thrown; a stop or unload failing after it is suppressed into it
        var broke: Throwable? = null
        // false in the finally on a return out of [use]: what is after it is not reached. An array, not a var:
        // a var set in the try read true in the finally on that return when inlined into another module
        val through = booleanArrayOf(false)
        var after: Throwable? = null
        try {
            start()
            // an Error too: sherpa's library not loading, memory running out
            detector = try {
                load()
            } catch (e: Throwable) {
                failed(e)
                null
            }
            use(detector)
            through[0] = true
        } catch (e: Throwable) {
            broke = e
            throw e
        } finally {
            after = close(detector, stop, unload, broke, returned = !through[0] && broke == null, failed)
        }
        // reached only with nothing broken before: the stop's or the unload's failure
        after?.let { throw it }
    }

    /**
     * [stop] and [unload] [detector]; their failure, suppressed into [broke] if something broke before. On a
     * return out of `use` ([returned]) told to [failed], not thrown over the return (and null returned).
     */
    @PublishedApi
    @Suppress("TooGenericExceptionCaught")
    internal fun <V : Any> close(detector: V?, stop: () -> Unit, unload: (V) -> Unit, broke: Throwable?, returned: Boolean, failed: (Throwable) -> Unit): Throwable? {
        var after: Throwable? = null
        try {
            stop()
        } catch (e: Throwable) {
            after = suppress(broke ?: after, e)
        }
        try {
            detector?.let(unload)
        } catch (e: Throwable) {
            after = suppress(broke ?: after, e)
        }
        if (returned && after != null) {
            failed(after)
            return null
        }
        return after.takeIf { broke == null }
    }

    /** [e] the first failure, or suppressed into the one before it (not into itself: the same one thrown again) */
    fun suppress(first: Throwable?, e: Throwable): Throwable = first?.apply { if (this !== e) addSuppressed(e) } ?: e
}
