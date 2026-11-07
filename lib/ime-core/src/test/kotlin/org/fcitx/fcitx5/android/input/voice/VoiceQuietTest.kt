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

    // resources/voice/quiet_golden.py: real clips in a room's noise, Gain2, silero and the recognizer's
    // own segments as the app has them
    @Test
    fun goldenCases() {
        val lines = javaClass.getResourceAsStream("/voice/quiet-golden.tsv")!!.bufferedReader().readLines()
        val got = lines.associate { line ->
            val f = line.split('\t')
            val quiet = VoiceQuiet()
            for (w in f.drop(5)) {
                val (p, v) = w.split(':')
                quiet.window(window(p.toDouble()), speech = v == "1")
            }
            val keeps = quiet.keeps(f[1], f[2].toLong(), f[3].toLong())
            assertEquals(f[0], f[4] == "1", keeps)
            f[0].substringBefore('#') to keeps
        }
        // the room's 「I'm sorry.」 goes
        for (room in listOf("white-66", "pink-60")) assertFalse(room, got.getValue(room))
        // speech of every kind stays: soft Chinese, soft English over the room, a lone word at a normal level
        for (speech in listOf("loud-english", "ok", "no", "yes", "hi", "soft-english-over-room", "q44-in-room", "soft-chinese")) {
            assertTrue(speech, got.getValue(speech))
        }
        // a known leak: a fan 30 dB down is loud and voiced, its 「I'm sorry.」 typed (specs/app.md, Quiet)
        assertTrue(got.getValue("fan"))
    }

    @Test
    fun noDetectorKeepsEverything() {
        // silero failed to load (VoiceOpening): the rule lets all through rather than lose speech
        val quiet = VoiceQuiet()
        repeat(90) { quiet.window(window(db(-66.0)), speech = null) }
        assertTrue(quiet.keeps("I'm sorry.", 0, 90L * 512))
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
    fun digitalSilenceIsNoFloor() {
        // the UAT clip microphone between clips: a ±1 sample in 128, -111 dBFS a window
        val sparse = FloatArray(512) { if (it % 128 == 0) 1f / 32768 else 0f }
        val quiet = VoiceQuiet()
        repeat(200) { quiet.window(sparse) }
        quiet.feed(db(-60.0), 40, voiced = true)
        // the room at -60 alone is the floor: its own 「I'm sorry.」 is not 50 dB over anything
        assertFalse(quiet.keeps("I'm sorry.", 200L * 512, 240L * 512))
        // as all-zero windows would be
        val zeroes = VoiceQuiet()
        repeat(200) { zeroes.window(FloatArray(512)) }
        zeroes.feed(db(-60.0), 40, voiced = true)
        assertFalse(zeroes.keeps("I'm sorry.", 200L * 512, 240L * 512))
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
