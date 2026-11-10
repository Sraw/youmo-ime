/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.voice.VoiceClipMic.Part
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    // the clip microphone is one object for the JVM: a test failing midway would leave it on, its clips queued,
    // and a later test class's feed() reading them instead of the microphone
    @After
    fun tearDown() = VoiceClipMic.clear()

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
        assertTrue((100 until 260).all { abs(buf[it]) < 1e-3f } && (100 until 260).any { buf[it] != 0f })
        assertTrue((260 until 360).all { buf[it] == -0.5f })
        assertTrue((360 until 500).all { abs(buf[it]) < 1e-3f } && (360 until 500).any { buf[it] != 0f })
        // the queue run dry: noise for as long as the talk lasts, never a window of exact zeroes
        repeat(20) {
            f.read(buf)
            assertTrue(buf.all { abs(it) < 1e-3f } && buf.any { it != 0f })
        }
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
        assertEquals("on=true talking=false playing=false queued=c,800ms", VoiceClipMic.state())
        val f = VoiceClipMic.feed()!!
        assertEquals("on=true talking=true playing=false queued=c,800ms", VoiceClipMic.state())
        f.close()
        VoiceClipMic.clear()
        assertEquals("on=false talking=false playing=false queued=", VoiceClipMic.state())
    }

    @Test
    fun theTalkEndingMidClipLeavesNothingPlaying() {
        VoiceClipMic.clear()
        VoiceClipMic.queue(listOf(clip(rate, 0.5f)))
        val f = VoiceClipMic.feed()!!
        f.read(FloatArray(512))
        assertEquals("on=true talking=true playing=true queued=", VoiceClipMic.state())
        f.close()
        assertEquals("on=true talking=false playing=false queued=", VoiceClipMic.state())
        VoiceClipMic.clear()
    }

    @Test
    fun takingAPartSaysPlayingAtOnce() {
        VoiceClipMic.clear()
        VoiceClipMic.queue(listOf(clip(10, 0.5f)))
        val f = VoiceClipMic.feed()!!
        VoiceClipMic.take(f)
        // no moment with the queue empty and nothing playing, before the Feed says so itself
        assertEquals("on=true talking=true playing=true queued=", VoiceClipMic.state())
        f.close()
        VoiceClipMic.clear()
        assertEquals("on=false talking=false playing=false queued=", VoiceClipMic.state())
    }

    @Test
    fun aTalkEndingAfterTheNextBeganLeavesTheNextAlone() {
        VoiceClipMic.clear()
        VoiceClipMic.queue(listOf(clip(rate, 0.5f), clip(rate, -0.5f)))
        val a = VoiceClipMic.feed()!!
        val b = VoiceClipMic.feed()!!
        val buf = FloatArray(512)
        b.read(buf)
        assertEquals(0.5f, buf[0])
        // A's finally comes late: it takes nothing and stops nothing
        a.read(buf)
        assertTrue(buf.all { it != 0.5f && it != -0.5f })
        a.close()
        assertEquals("on=true talking=true playing=true queued=c", VoiceClipMic.state())
        b.read(buf)
        assertEquals(0.5f, buf[0])
        b.close()
        VoiceClipMic.clear()
    }

    @Test
    fun clearStopsTheClipTheFeedIsPlaying() {
        VoiceClipMic.clear()
        VoiceClipMic.queue(listOf(clip(rate, 0.5f)))
        val f = VoiceClipMic.feed()!!
        val buf = FloatArray(512)
        f.read(buf)
        assertEquals(0.5f, buf[0])
        VoiceClipMic.clear()
        f.read(buf)
        assertTrue(buf.all { it != 0.5f })
        assertEquals("on=false talking=true playing=false queued=", VoiceClipMic.state())
        f.close()
    }

    // the feed's own side of clearing, without the singleton: a generation changed mid clip stops it and says
    // so once (busy true then false), which the test above, through clear(), does not see
    @Test
    fun clearingStopsTheClipPlaying() {
        var gen = 0
        val busy = mutableListOf<Boolean>()
        val q = ArrayDeque<Part>(listOf(clip(rate, 0.5f)))
        val time = Time()
        val f = VoiceClipMic.Feed(rate, { q.removeFirstOrNull() }, { time.ns }, time::sleep, { gen }, { busy += it })
        val buf = FloatArray(512)
        f.read(buf)
        assertEquals(0.5f, buf[0])
        gen++
        f.read(buf)
        assertTrue(buf.all { it != 0.5f })
        assertEquals(listOf(true, false), busy)
    }

    @Test
    fun theLastWindowOfAClipIsNotBusyAfter() {
        val busy = mutableListOf<Boolean>()
        val q = ArrayDeque<Part>(listOf(clip(512, 0.5f)))
        val time = Time()
        val f = VoiceClipMic.Feed(rate, { q.removeFirstOrNull() }, { time.ns }, time::sleep, busy = { busy += it })
        f.read(FloatArray(512))
        assertEquals(listOf(false), busy)
    }

    @Test
    fun eachClipSaysWhenItStarts() {
        val started = mutableListOf<Pair<String, Long>>()
        val q = ArrayDeque<Part>(listOf(Part.Clip("a", FloatArray(600)), Part.Pause(10), Part.Clip("b", FloatArray(10))))
        val time = Time()
        val f = VoiceClipMic.Feed(rate, { q.removeFirstOrNull() }, { time.ns }, time::sleep, started = { n, at -> started += n to at })
        f.read(FloatArray(512))
        assertEquals(listOf("a" to 0L), started)
        f.read(FloatArray(512))
        // 600 of a, then 10 ms (160 samples) of pause
        assertEquals(listOf("a" to 0L, "b" to 760L), started)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aPauseOverAMinuteIsRefused() {
        Part.Pause(60_001)
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

    @Test
    fun aWavItCannotReadIsRefused() {
        val good = wav(shortArrayOf(0, 1))
        assertRows(
            // the voice models take 16 kHz: another rate would be heard sped up or slowed down
            "a WAV at 44.1 kHz" to { refused(wav(shortArrayOf(0, 1), rate = 44100)) },
            // the models take one channel: two interleaved would be heard as one at twice the rate
            "a stereo WAV" to { refused(wav(shortArrayOf(0, 1), channels = 2)) },
            // a RIFF file of another kind is not sound
            "RIFF but not WAVE" to { refused(good.copyOf().also { "AVI ".toByteArray().copyInto(it, 8) }) },
            // its header says 16 bytes of fmt, 4 follow
            "fmt cut short" to { refused(good.copyOf(12 + 8 + 4)) },
            // the samples' format not yet known when they come
            "data before fmt" to { refused(good.copyOf(12) + "data".toByteArray() + byteArrayOf(4, 0, 0, 0, 0, 0, 1, 0)) },
        )
    }

    private fun refused(bad: ByteArray) {
        assertThrows(IllegalArgumentException::class.java) { VoiceClipMic.decode(bad) }
    }
}
