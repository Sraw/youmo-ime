/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class VoiceVadHandoffTest {

    // speech is a window of samples above 0; a stretch is cut at the first silent window after it
    // (or by flush), as (start, samples fed in it)
    private class FakeVad : VoiceVadHandoff.Vad<Pair<Int, Int>> {
        val fed = mutableListOf<Float>()
        private val cut = ArrayDeque<Pair<Int, Int>>()
        private var from = -1
        var flushed = false

        override fun accept(samples: FloatArray) {
            val speech = samples.any { it > 0 }
            if (speech && from < 0) from = fed.size
            if (!speech && from >= 0) close()
            samples.forEach { fed += it }
        }

        private fun close() {
            cut.addLast(from to fed.size - from)
            from = -1
        }

        override fun isSpeech() = from >= 0
        override fun empty() = cut.isEmpty()
        override fun front() = cut.first()
        override fun pop() {
            cut.removeFirst()
        }

        override fun flush() {
            flushed = true
            if (from >= 0) close()
        }

        override fun start(segment: Pair<Int, Int>) = segment.first
    }

    private val started = mutableListOf<Int>()
    private val stretches = mutableListOf<Triple<Int, Int, Long>>()
    private fun handoff(most: Int = 1000) = VoiceVadHandoff(most, object : VoiceVadHandoff.Events<Pair<Int, Int>> {
        override fun speechStarted() {
            started += stretches.size
        }

        override fun stretch(segment: Pair<Int, Int>, dropped: Long) {
            stretches += Triple(segment.first, segment.second, dropped)
        }
    })

    private fun w(v: Float) = FloatArray(4) { v }

    @Test
    fun aLiftOrCutBeforeTheVadStillHearsWhatWasSaid() {
        val h = handoff()
        h.window(w(0f))
        h.window(w(1f))
        h.window(w(2f))
        assertEquals(0, stretches.size)
        val vad = FakeVad()
        // the VAD comes only as the opening ends: fed all that was held, in order, the last stretch flushed
        assertSame(vad, h.end(vad))
        assertEquals(listOf(0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 2f, 2f, 2f, 2f), vad.fed)
        assertEquals(true, vad.flushed)
        assertEquals(listOf(Triple(4, 8, 0L)), stretches)
        assertEquals(listOf(0), started)
        assertEquals(4L, h.lastStart)
        assertEquals(1, h.segments)
    }

    @Test
    fun closedBeforeTheVadNothingIsHeard() {
        val h = handoff()
        h.window(w(1f))
        h.close()
        // VoiceListener offers the late VAD back as a spare and ends with none
        assertNull(h.end(null))
        assertEquals(0, stretches.size)
        // were one handed in after all, what was held is gone
        val vad = FakeVad()
        h.end(vad)
        assertEquals(emptyList<Float>(), vad.fed)
        assertEquals(0, stretches.size)
    }

    @Test
    fun theVadInMidwayHearsTheHeldFirstThenEachWindowAsRead() {
        val h = handoff()
        h.window(w(1f))
        val vad = FakeVad()
        h.arrived(vad)
        assertEquals(4, vad.fed.size)
        h.window(w(0f))
        // cut at the silent window, as it is read
        assertEquals(listOf(Triple(0, 4, 0L)), stretches)
        h.window(w(3f))
        assertSame(vad, h.end(null))
        assertEquals(listOf(Triple(0, 4, 0L), Triple(8, 4, 0L)), stretches)
        assertEquals(listOf(0, 1), started)
        assertEquals(2, h.segments)
    }

    @Test
    fun whatTheBacklogDroppedShiftsWhereAStretchStartsInTheRecord() {
        // room for two windows: the first of three goes
        val h = handoff(most = 8)
        h.window(w(0f))
        h.window(w(0f))
        h.window(w(5f))
        assertEquals(4L, h.dropped)
        h.end(FakeVad())
        // the VAD's start 4 is the record's 8
        assertEquals(listOf(Triple(4, 4, 4L)), stretches)
        assertEquals(8L, h.lastStart)
    }
}
