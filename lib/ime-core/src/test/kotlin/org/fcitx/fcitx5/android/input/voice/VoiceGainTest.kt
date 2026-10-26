/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class VoiceGainTest {

    private val rate = 16000
    private val window = 512

    // a 200 Hz tone at [dbfs] RMS
    private fun tone(windows: Int, dbfs: Double): FloatArray {
        val a = sqrt(2.0) * 10.0.pow(dbfs / 20)
        return FloatArray(windows * window) { (sin(2 * PI * 200 * it / rate) * a).toFloat() }
    }

    private fun db(x: FloatArray, from: Int = 0, until: Int = x.size): Double {
        var sum = 0.0
        for (i in from until until) sum += x[i].toDouble() * x[i]
        return 10 * log10(sum / (until - from) + 1e-12)
    }

    // through a gain a window at a time, as VoiceListener does; the gain after each window
    private fun through(gain: VoiceGain, x: FloatArray): DoubleArray {
        val gains = DoubleArray(x.size / window)
        for (w in gains.indices) {
            val part = x.copyOfRange(w * window, (w + 1) * window)
            gain.apply(part)
            part.copyInto(x, w * window)
            gains[w] = gain.gain
        }
        return gains
    }

    private fun dB(factor: Double) = 20 * log10(factor)

    @Test
    @Uat("VO-24")
    fun sameAsTheMeasuredOneSampleForSample() {
        // resources/voice/gain_golden.py (tailquiet's Gain2): each parameter changed alone changes it.
        // contract: a fixture generated from the measured Python gain, which mutation cannot see.
        val bytes = javaClass.getResourceAsStream("/voice/gain-golden.bin")!!.use { it.readBytes() }
        val all = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val n = all.remaining() / 2
        val x = FloatArray(n).also { all.get(it) }
        val expected = FloatArray(n).also { all.get(it) }
        assertEquals(0, n % window)
        through(VoiceGain(rate), x)
        var worst = 0f
        for (i in 0 until n) worst = maxOf(worst, abs(x[i] - expected[i]))
        assertTrue("worst $worst", worst <= 1e-5f)
    }

    @Test
    @Uat("VO-24")
    fun aSteadyLevelIsLiftedToTheTargetWithinTheLimits() = assertRows(
        "-55: lifted at most 20 dB, so the second second comes out at some -35, not the -30 target" to {
            val x = tone(62, -55.0)
            val gains = through(VoiceGain(rate), x)
            assertEquals(VoiceGain.MAX_DB, dB(gains.last()), 1e-9)
            assertEquals(-35.0, db(x, 31 * window), 0.1)
        },
        "-40: off both limits, what the -30 target itself does (512 samples hold 6.4 periods: a little off)" to {
            val gains = through(VoiceGain(rate), tone(40, -40.0))
            assertEquals(10.0, dB(gains.last()), 0.1)
        },
        "-20: speech at its usual level passes as it came, sample for sample" to {
            val x = tone(62, -20.0)
            val y = x.copyOf()
            val gains = through(VoiceGain(rate), y)
            assertTrue(gains.all { it == 1.0 })
            assertArrayEquals(x, y, 0f)
        },
    )

    @Test
    fun aOneSampleReadRisesStraightToTheGain() {
        // no ramp across one sample (step over count - 1 would be 0/0): it gets the gain itself
        val gain = VoiceGain(rate)
        through(gain, tone(VoiceGain.SKIP + VoiceGain.RANK - 1, -55.0))
        val one = floatArrayOf(0.001f)
        gain.apply(one)
        assertEquals(VoiceGain.MAX_DB, dB(gain.gain), 1e-9)
        assertEquals(0.01f, one[0], 1e-7f)
    }

    // env falls 3 dB a second of samples once the loud has left the span: the gain rises with it. A
    // half window too: VoiceListener hands a short read on as it came (copyOf(n)), and whole windows
    // alone could not tell a fall per sample from one per window
    @Test
    fun afterLoudTheGainComesBackAtThreeDbASecond() = assertRows(
        "whole windows" to {
            val gains = through(VoiceGain(rate), tone(31, -20.0) + tone(200, -45.0))
            assertEquals(1.0, gains[31 + 100], 0.0)
            assertEquals(20 * VoiceGain.DECAY_DB_S * window / rate, dB(gains[31 + 160]) - dB(gains[31 + 140]), 1e-6)
        },
        "half windows: by their own samples" to {
            val half = window / 2
            val gain = VoiceGain(rate)
            through(gain, tone(31, -20.0))
            val quiet = tone(200, -45.0)
            val gains = DoubleArray(quiet.size / half) { h ->
                gain.apply(quiet.copyOfRange(h * half, (h + 1) * half))
                gain.gain
            }
            assertEquals(40 * VoiceGain.DECAY_DB_S * half / rate, dB(gains[31 + 340]) - dB(gains[31 + 300]), 1e-6)
        },
    )

    @Test
    fun speechAfterQuietIsLiftedForTwoWindowsAtMost() {
        val x = tone(31, -65.0) + tone(5, -20.0)
        val gains = through(VoiceGain(rate), x)
        // the 3rd loudest still the quiet: held by the peak only, then the usual 0 dB
        assertTrue(dB(gains[31]) > 15 && dB(gains[32]) > 15)
        assertTrue(gains.drop(33).all { it == 1.0 })
        assertTrue(x.copyOfRange(31 * window, 33 * window).maxOf { abs(it) } <= 0.99f)
    }

    @Test
    fun suddenlyLoudIsNeverClipped() {
        val x = tone(40, -55.0) + tone(1, -6.0) + tone(5, -55.0)
        through(VoiceGain(rate), x)
        val loud = x.copyOfRange(40 * window, 41 * window)
        assertTrue(loud.maxOf { abs(it) } <= 0.99f)
    }

    @Test
    fun aClickDoesNotHoldTheGainDown() {
        val click = tone(1, -10.0)
        val x = tone(20, -55.0) + click + tone(31, -55.0)
        val gains = through(VoiceGain(rate), x)
        // held down for the click's own window only (its peak), back up from the next window
        assertTrue(dB(gains[20]) < 15)
        assertTrue(gains.drop(21).all { dB(it) >= 15 })
    }

    @Test
    fun theStartUpPopIsLeftOut() {
        // three loud windows as the microphone opens, quiet after: none of them counts
        val x = tone(VoiceGain.SKIP, -6.0) + tone(31, -55.0)
        val gains = through(VoiceGain(rate), x)
        assertTrue(gains.take(VoiceGain.SKIP + VoiceGain.RANK - 1).all { it == 1.0 })
        assertEquals(VoiceGain.MAX_DB, dB(gains.last()), 1e-9)
    }

    // all-zero windows before the microphone starts neither count towards SKIP nor enter the level
    @Test
    fun zeroesBeforeTheMicrophoneStartsDoNotUseUpTheSkip() {
        val signal = tone(VoiceGain.SKIP, -6.0) + tone(31, -55.0)
        val alone = signal.copyOf().also { through(VoiceGain(rate), it) }
        val afterZeros = FloatArray(10 * window) + signal
        through(VoiceGain(rate), afterZeros)
        assertArrayEquals(alone, afterZeros.copyOfRange(10 * window, afterZeros.size), 0f)
    }

    @Test
    fun silenceStaysSilentAndTheSameInputGivesTheSame() {
        val zeros = FloatArray(40 * window)
        through(VoiceGain(rate), zeros)
        assertTrue(zeros.all { it == 0f })
        val a = tone(20, -50.0) + tone(10, -25.0)
        val b = a.copyOf()
        through(VoiceGain(rate), a)
        through(VoiceGain(rate), b)
        assertArrayEquals(a, b, 0f)
    }

    @Test
    fun theDebugLogsNumbers() {
        val gain = VoiceGain(rate)
        assertEquals(null, gain.envPeak)
        through(gain, tone(40, -55.0))
        assertEquals(-55.0, gain.envPeak!!, 0.1)
        assertEquals(VoiceGain.MAX_DB, dB(gain.peakGain), 1e-9)
    }
}
