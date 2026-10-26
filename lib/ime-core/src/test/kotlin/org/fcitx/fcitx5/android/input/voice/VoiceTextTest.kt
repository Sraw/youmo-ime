/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceTextTest {

    private fun check(expected: String, raw: String) = assertEquals(raw, expected, VoiceText.clean(raw))

    @Test
    fun spelledLettersAreOneAbbreviation() {
        // \b back fails this only on Java up to 18 (CI's 17), and éA B below on 19+
        check("订了CEP的票", "订了 C E P 的票")
        check("an AB test", "an A B test")
    }

    @Test
    fun aLetterGluedToAWordIsNotSpelled() {
        check("3C E", "3C E")
        check("CE Pa", "C E Pa")
        check("x_A B", "x_A B")
        check("éA B", "éA B")
    }

    // what voice-eval's noise set (coughs, throat clearing, breathing) came back as
    @Test
    fun mouthNoisesTypeNothing() {
        listOf(
            "咳咳咳咳咳咳咳咳咳咳咳咳。", "咳咳咳咳。", "咳咳咳咳咳咳咳咳咳咳咳咳咳咳咳咳", "哼哼哼", "哼哼哼。",
            "哼哼哼哼哼哼。", "嗯哼哼哼。", "Huh,", "Huh。", "Huh.", "hmm", "Hmm... uh", "嗯。", "uhhm", "Uh huh, hmm.", "Ah", "Ah.",
            // LONGEST_SOUND: a sound word up to 64 letters still counts
            "m".repeat(64),
        ).forEach { assertNull(it, VoiceText.clean(it)) }
    }

    @Test
    fun aSoundWithOtherWordsStillTypes() {
        check("哼。", "哼。")
        check("哼哈哈", "哼哈哈")
        check("嗯哼因为感觉香港好多深圳人", "嗯哼因为感觉香港好多深圳人")
        check("阿牛哥，晒命通告最好表贴酱大张，哼哼。", "阿牛哥，晒命通告最好表贴酱大张，哼哼。")
        check("这时，刘志冷哼一声，咳嗽了几下。", "这时，刘志冷哼一声，咳嗽了几下。")
        check("咳咳，大家好", "咳咳，大家好")
        check("Huh, really?", "Huh, really?")
        check("哎呦喂。", "哎呦喂。")
        check("hummus", "hummus")
        // F4272: one letter past LONGEST_SOUND is a word, not a hum
        check("m".repeat(65), "m".repeat(65))
    }

    // F3810: one `(sound pause*)+` regex split "mmmm…m okay" 2^n ways before failing, which hangs
    // rather than fails; a generous timeout, not a wall-clock bound that a loaded CI can miss
    @Test(timeout = 2_000)
    fun aLongHumBeforeAWordIsCheckedFast() {
        // typed whole: "okay" is no sound
        check("m".repeat(60) + " okay", "m".repeat(60) + " okay")
    }
}
