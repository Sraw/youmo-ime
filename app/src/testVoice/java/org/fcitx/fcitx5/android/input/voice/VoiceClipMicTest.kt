/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.voice.VoiceClipMic.Part
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class VoiceClipMicTest {

    private val rate = 16000

    /** a clock that only the sleeps move, and a record of them */
    private class Time {
        var ns = 0L
        val slept = mutableListOf<Long>()
        fun sleep(ms: Long) {
            slept += ms
            ns += ms * 1_000_000
        }
    }

    private fun feed(parts: List<Part>, time: Time): VoiceClipMic.Feed {
        val q = ArrayDeque(parts)
        return VoiceClipMic.Feed(rate, { q.removeFirstOrNull() }, { time.ns }, time::sleep)
    }

    private fun clip(n: Int, v: Float) = Part.Clip("c", FloatArray(n) { v })

    @Test
    fun windowsComeAtTheMicrophonesPaceNotAllAtOnce() {
        val time = Time()
        val f = feed(listOf(clip(rate, 0.5f)), time)
        val buf = FloatArray(1600)   // 100 ms
        repeat(10) { f.read(buf) }
        // each window waited for: a second's worth read in a second, not at once
        assertEquals(1000L, time.slept.sum())
        assertTrue(time.slept.all { it == 100L })
    }

    @Test
    fun aReaderFallingBehindIsNotMadeToWait() {
        val time = Time()
        val f = feed(listOf(clip(rate, 0.5f)), time)
        val buf = FloatArray(1600)
        f.read(buf)
        time.ns += 500_000_000   // half a second late
        repeat(4) { f.read(buf) }
        assertEquals(listOf(100L), time.slept)
    }

    @Test
    fun clipsInOrderWithThePauseBetweenThenNoiseNeverZero() {
        val time = Time()
        val f = feed(listOf(clip(100, 0.5f), Part.Pause(10), clip(100, -0.5f)), time)
        val buf = FloatArray(500)
        f.read(buf)
        assertTrue((0 until 100).all { buf[it] == 0.5f })
        // 10 ms at 16 kHz: 160 samples of noise
        assertTrue((100 until 260).all { buf[it] != 0f && abs(buf[it]) < 1e-3f })
        assertTrue((260 until 360).all { buf[it] == -0.5f })
        assertTrue((360 until 500).all { buf[it] != 0f && abs(buf[it]) < 1e-3f })
        // the queue run dry: noise for as long as the talk lasts
        f.read(buf)
        assertTrue(buf.all { it != 0f && abs(it) < 1e-3f })
    }

    @Test
    fun aClipSpansWindows() {
        val time = Time()
        val f = feed(listOf(Part.Clip("c", FloatArray(700) { it.toFloat() })), time)
        val a = FloatArray(512)
        val b = FloatArray(512)
        f.read(a)
        f.read(b)
        assertEquals(511f, a[511])
        assertEquals(512f, b[0])
        assertEquals(699f, b[187])
        assertTrue(abs(b[188]) < 1e-3f)
    }

    @Test
    fun theQueueIsOnTillClearedAndSaysWhatIsInIt() {
        VoiceClipMic.clear()
        assertEquals(null, VoiceClipMic.feed())
        VoiceClipMic.queue(listOf(clip(10, 0.1f), Part.Pause(800)))
        assertEquals("on=true playing=false queued=c,800ms", VoiceClipMic.state())
        assertTrue(VoiceClipMic.feed() != null)
        VoiceClipMic.clear()
        assertEquals("on=false playing=false queued=", VoiceClipMic.state())
    }

    private fun wav(samples: ShortArray, rate: Int = 16000, channels: Int = 1, extra: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        fun le(n: Int, bytes: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array().copyOf(bytes)
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()
        out.write("RIFF".toByteArray()); out.write(le(0, 4)); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(le(16, 4)); out.write(le(1, 2)); out.write(le(channels, 2))
        out.write(le(rate, 4)); out.write(le(rate * 2 * channels, 4)); out.write(le(2 * channels, 2)); out.write(le(16, 2))
        if (extra) {
            out.write("LIST".toByteArray()); out.write(le(3, 4)); out.write(byteArrayOf(1, 2, 3, 0))
        }
        out.write("data".toByteArray()); out.write(le(data.size, 4)); out.write(data)
        return out.toByteArray()
    }

    @Test
    fun aWavOrRawPcmIsDecoded() {
        val s = shortArrayOf(0, 16384, -32768, 32767)
        val want = floatArrayOf(0f, 0.5f, -1f, 32767 / 32768f)
        assertArrayEquals(want, VoiceClipMic.decode(wav(s)), 0f)
        assertArrayEquals(want, VoiceClipMic.decode(wav(s, extra = true)), 0f)
        val raw = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply { s.forEach { putShort(it) } }.array()
        assertArrayEquals(want, VoiceClipMic.decode(raw), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aWavAtAnotherRateIsRefused() {
        VoiceClipMic.decode(wav(shortArrayOf(0, 1), rate = 44100))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aStereoWavIsRefused() {
        VoiceClipMic.decode(wav(shortArrayOf(0, 1), channels = 2))
    }
}
