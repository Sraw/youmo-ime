/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * What the microphone reads before the VAD is there to hear it: kept in order and handed over
 * once it is ([ready]), so the microphone is read from the moment it is on whatever is still
 * loading, and nothing said meanwhile is lost. At most [most] samples: past them the oldest go,
 * as a record's own buffer would overrun.
 */
class VoiceBacklog(private val most: Int) {

    init {
        require(most > 0) { "most $most" }
    }

    private val held = ArrayDeque<FloatArray>()
    private var heldSamples = 0

    /** samples dropped at the front for want of room: the VAD's positions are behind the record's by this many */
    var dropped = 0L
        private set

    var ready = false
        private set

    /** One window read: what to hand the VAD now, in order (none while it is not [ready]). */
    fun window(samples: FloatArray): List<FloatArray> {
        if (ready) return listOf(samples)
        held.addLast(samples)
        heldSamples += samples.size
        while (heldSamples > most) {
            val first = held.removeFirst()
            heldSamples -= first.size
            dropped += first.size
        }
        return emptyList()
    }

    /** The VAD there: what was held, in order, handed over once; from now on each window as read. */
    fun start(): List<FloatArray> {
        ready = true
        val all = held.toList()
        held.clear()
        heldSamples = 0
        return all
    }

    /** The opening over before the VAD came: what was held goes. */
    fun drop() {
        held.clear()
        heldSamples = 0
    }

    val heldCount get() = heldSamples
}
