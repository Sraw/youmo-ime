/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class VoiceQuietTest {

    private val random = Random(1)

    // a window of white noise at [db] dBFS rms
    private fun noise(db: Double) = FloatArray(512) { (random.nextGaussian() * Math.pow(10.0, db / 20)).toFloat() }

    // a window of a tone at [db] dBFS rms: speech's stand-in
    private fun tone(db: Double) = FloatArray(512) { i -> (Math.sqrt(2.0) * Math.pow(10.0, db / 20) * sin(2 * PI * 220 * i / 16000)).toFloat() }

    private fun Random.nextGaussian(): Double {
        // Box-Muller
        val u = 1 - nextDouble()
        return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * PI * nextDouble())
    }

    @Test
    fun aQuietRoomAloneIsNotSpeech() {
        // the OnePlus 6T's room, 2026-11-06: -66 dBFS, typed 「I'm sory」 once lifted 20 dB
        val quiet = VoiceQuiet()
        repeat(90) { quiet.window(noise(-66.0)) }
        assertFalse(quiet.speech)
    }

    @Test
    fun aLouderSteadyNoiseIsNotSpeechEither() {
        val quiet = VoiceQuiet()
        repeat(90) { quiet.window(noise(-56.0)) }
        assertFalse(quiet.speech)
    }

    @Test
    fun softSpeechAboveTheRoomIsSpeech() {
        // -58 dBFS said softly over the -66 room: under LOUD, 8 dB over the floor
        val quiet = VoiceQuiet()
        repeat(30) { quiet.window(noise(-66.0)) }
        repeat(20) { quiet.window(tone(-57.0)) }
        repeat(30) { quiet.window(noise(-66.0)) }
        assertTrue(quiet.speech)
    }

    @Test
    fun softSpeechInDigitalSilenceIsSpeech() {
        // the device UAT's -q44 clips: speech at -59, zeroes around it
        val quiet = VoiceQuiet()
        repeat(20) { quiet.window(FloatArray(512)) }
        repeat(20) { quiet.window(tone(-59.0)) }
        repeat(5) { quiet.window(FloatArray(512) { 1e-7f }) }
        assertTrue(quiet.speech)
    }

    @Test
    fun speechAtItsUsualLevelIsSpeechFromTheStart() {
        val quiet = VoiceQuiet()
        repeat(10) { quiet.window(tone(-25.0)) }
        assertTrue(quiet.speech)
    }

    @Test
    fun aClickOrTwoIsNotSpeech() {
        val quiet = VoiceQuiet()
        repeat(40) { quiet.window(noise(-66.0)) }
        // the start-up pop's left out, a click later is a window or two: the 3rd loudest stays the room
        repeat(2) { quiet.window(tone(-20.0)) }
        repeat(40) { quiet.window(noise(-66.0)) }
        assertFalse(quiet.speech)
    }

    @Test
    fun theStartUpPopIsLeftOut() {
        val quiet = VoiceQuiet()
        repeat(3) { quiet.window(tone(-20.0)) }
        repeat(60) { quiet.window(noise(-66.0)) }
        assertFalse(quiet.speech)
    }

    @Test
    fun zeroesAloneAreNotSpeech() {
        // an emulator's microphone with no audio: the recognizer typed 「H」, 「I'm」 from them
        val quiet = VoiceQuiet()
        assertFalse(quiet.speech)
        repeat(50) { quiet.window(FloatArray(512)) }
        quiet.window(FloatArray(0))
        assertFalse(quiet.speech)
    }

    @Test
    fun onceSpeechItStays() {
        val quiet = VoiceQuiet()
        repeat(10) { quiet.window(tone(-30.0)) }
        repeat(300) { quiet.window(noise(-66.0)) }
        assertTrue(quiet.speech)
    }
}
