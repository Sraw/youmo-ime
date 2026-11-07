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
    inline fun <V : Any> run(start: () -> Unit, load: () -> V, use: (V?) -> Unit, stop: () -> Unit, unload: (V) -> Unit, failed: (Throwable) -> Unit = {}) {
        var detector: V? = null
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
        } finally {
            try {
                stop()
            } finally {
                detector?.let(unload)
            }
        }
    }
}
