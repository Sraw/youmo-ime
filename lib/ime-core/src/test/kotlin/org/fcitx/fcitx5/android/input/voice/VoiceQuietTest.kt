/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class VoiceQuietTest {

    // a window of [power] (mean square): the level is all VoiceQuiet reads of it
    private fun window(power: Double, n: Int = 512) = FloatArray(n) { sqrt(power).toFloat() }

    private fun db(d: Double) = Math.pow(10.0, d / 10)

    private fun VoiceQuiet.feed(power: Double, times: Int, voiced: Boolean = false) = repeat(times) { window(window(power), speech = voiced) }

    // resources/voice/quiet_golden.py: real clips in a room's noise, Gain2 and silero as the app has them
    @Test
    fun goldenCases() {
        val lines = javaClass.getResourceAsStream("/voice/quiet-golden.tsv")!!.bufferedReader().readLines()
        assertEquals(8, lines.size)
        val got = lines.map { line ->
            val f = line.split('\t')
            val quiet = VoiceQuiet()
            for (w in f.drop(5)) {
                val (p, v) = w.split(':')
                quiet.window(window(p.toDouble()), speech = v == "1")
            }
            val keeps = quiet.keeps(f[1], f[2].toLong(), f[3].toLong())
            assertEquals(f[0], f[4] == "1", keeps)
            f[0] to keeps
        }.toMap()
        // both ways: the room's 「I'm sorry.」 goes, speech of every kind stays
        assertFalse(got.getValue("white-66"))
        assertTrue(got.getValue("q44-in-room"))
        assertTrue(got.getValue("soft-english-over-room"))
    }

    @Test
    fun aChineseCharacterOrALongerTextIsAlwaysTyped() {
        val quiet = VoiceQuiet()
        quiet.feed(db(-66.0), 90)
        assertTrue(quiet.keeps("你好", 0, 90L * 512))
        assertTrue(quiet.keeps("I think this is a whole sentence", 0, 90L * 512))
        assertFalse(quiet.keeps("I'm sorry.", 0, 90L * 512))
    }

    @Test
    fun aShortLatinPhraseNeedsTheRoomLoudAndVoiced() {
        val quiet = VoiceQuiet()
        quiet.feed(db(-66.0), 40)
        quiet.feed(db(-55.0), 20, voiced = true)
        quiet.feed(db(-66.0), 40)
        quiet.feed(db(-55.0), 20)
        // voiced and 11 dB over the floor
        assertTrue(quiet.keeps("OK", 40L * 512, 60L * 512))
        // as loud, the VAD hearing none: a fan's hum, not speech
        assertFalse(quiet.keeps("OK", 100L * 512, 120L * 512))
        // voiced, not loud: the room's own stretch
        val soft = VoiceQuiet()
        soft.feed(db(-66.0), 60, voiced = true)
        assertFalse(soft.keeps("OK", 0, 60L * 512))
    }

    @Test
    fun loudSpeechIsLoudWhateverTheFloor() {
        val quiet = VoiceQuiet()
        quiet.feed(db(-45.0), 30, voiced = true)
        assertTrue(quiet.keeps("Yeah.", 0, 30L * 512))
    }

    @Test
    fun zeroesAndBrokenSamplesAreNoSound() {
        val quiet = VoiceQuiet()
        quiet.feed(0.0, 50)
        quiet.window(FloatArray(512) { Float.NaN })
        quiet.window(FloatArray(512) { Float.POSITIVE_INFINITY })
        quiet.window(FloatArray(0))
        // an emulator's silent microphone: the recognizer's 「H」 goes
        assertFalse(quiet.keeps("H", 0, 52L * 512))
    }

    @Test
    fun theSegmentIsTheWindowsItSpans() {
        val quiet = VoiceQuiet()
        quiet.feed(db(-66.0), 10)
        quiet.feed(db(-40.0), 5, voiced = true)
        quiet.feed(db(-66.0), 10)
        assertFalse(quiet.keeps("OK", 0, 10L * 512))
        // a sample into the third loud window takes it in: three loud ones are the level
        assertFalse(quiet.keeps("OK", 0, 12L * 512))
        assertTrue(quiet.keeps("OK", 0, 12L * 512 + 1))
        assertFalse(quiet.keeps("OK", 15L * 512, 25L * 512))
        // past what was read: what there is
        assertTrue(quiet.keeps("OK", 0, 1_000_000))
        // windows of any size
        val odd = VoiceQuiet()
        repeat(40) { odd.window(window(db(-40.0), 300), speech = true) }
        assertTrue(odd.keeps("OK", 0, 40L * 300))
        // many windows: the record grows
        val long = VoiceQuiet()
        long.feed(db(-66.0), 1000)
        assertFalse(long.keeps("OK", 0, 1000L * 512))
    }

    @Test
    fun latinShortIsWhatTheRoomIsWrittenAs() {
        assertTrue(VoiceQuiet.latinShort("I'm sorry."))
        assertTrue(VoiceQuiet.latinShort("H"))
        assertTrue(VoiceQuiet.latinShort("Look friends."))
        assertFalse(VoiceQuiet.latinShort("I'm sorry.嗯。"))
        assertFalse(VoiceQuiet.latinShort("abcdefghijklm"))
        assertFalse(VoiceQuiet.latinShort("。"))
        assertFalse(VoiceQuiet.latinShort(""))
        // Extension B: a Han character all the same
        assertFalse(VoiceQuiet.latinShort("𠀀"))
    }
}
