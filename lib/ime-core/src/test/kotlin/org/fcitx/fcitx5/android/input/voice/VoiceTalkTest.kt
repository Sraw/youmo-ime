/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTalkTest {

    private fun VoiceTalk.step(whole: String, settled: Int) = heard(VoiceLive.Heard(whole, settled)).let { it.type to it.composing }

    @Test
    fun holdingTypesNothingTillTheEnd() {
        val talk = VoiceTalk(asItGoes = false)
        assertEquals("" to "今天天气不错，我们", talk.step("今天天气不错，我们", 7))
        assertFalse(talk.typedAny)
        assertEquals("今天天气不错，我们走", talk.rest("今天天气不错，我们走"))
    }

    @Test
    fun tapToTalkTypesTheSettledPartAsItGoesAndComposesTheTail() {
        val talk = VoiceTalk(asItGoes = true)
        // nothing settled yet: all of it composes
        assertEquals("" to "今天天气不错", talk.step("今天天气不错", 0))
        assertEquals("今天天气不错，" to "我们出去", talk.step("今天天气不错，我们出去", 7))
        assertTrue(talk.typedAny)
        // the same settled part again: typed once
        assertEquals("" to "我们出去走走", talk.step("今天天气不错，我们出去走走", 7))
        assertEquals("我们出去走走。" to "好吗", talk.step("今天天气不错，我们出去走走。好吗", 14))
        // stopped: only what is left
        assertEquals("好吗？", talk.rest("今天天气不错，我们出去走走。好吗？"))
    }

    @Test
    fun eachPartIsCleanedOnItsOwn() {
        val talk = VoiceTalk(asItGoes = true)
        assertEquals("二零二六年，" to "我们", talk.step("二零二六年，我们", 6))
        assertEquals("我们见", talk.rest("二零二六年，我们见"))
    }

    @Test
    fun aSoundAloneBetweenMarksTypesNothing() {
        val talk = VoiceTalk(asItGoes = true)
        assertEquals("" to "我们走", talk.step("嗯，我们走", 2))
        assertFalse(talk.typedAny)
        assertNull(talk.rest("嗯，"))
    }

    @Test
    fun whatWasHeardLastIsLeftWhenItEndsEarly() {
        val talk = VoiceTalk(asItGoes = true)
        assertNull(talk.restNow())
        talk.step("今天天气不错，我们出去", 7)
        assertEquals("我们出去", talk.restNow())
    }

    @Test
    fun aSettledPartChangedAfterAllIsNotDropped() {
        val talk = VoiceTalk(asItGoes = true)
        talk.step("今天天气不错，我们", 7)
        // a rewrite before what was typed: from where they differ
        assertEquals("很好，我们", talk.rest("今天天气很好，我们"))
        assertNull(talk.rest(null))
        // a later step that does not start with it types nothing more
        assertEquals("" to "很好，我们走", talk.step("今天天气很好，我们走", 7))
    }

    @Test
    fun whatTheAppTookIsNotTypedAgain() {
        val talk = VoiceTalk(asItGoes = true)
        talk.step("今天天气不错，我们出去", 7)
        // the app sent the field's text, the composing 我们出去 with it
        talk.consumed()
        assertTrue(talk.typedAny)
        assertEquals("" to "走走", talk.step("今天天气不错，我们出去走走", 7))
        assertEquals("走走。", talk.rest("今天天气不错，我们出去走走。"))
        // holding to talk: the same, all it composed taken
        val held = VoiceTalk(asItGoes = false)
        held.step("你好", 0)
        held.consumed()
        assertNull(held.rest("你好"))
    }

    @Test
    fun nothingTypedWhileTheFieldComposesTheEngines() {
        val talk = VoiceTalk(asItGoes = true)
        assertEquals("", talk.heard(VoiceLive.Heard("今天天气不错，我们出去", 7), typing = false).type)
        assertFalse(talk.typedAny)
        // typing again: the settled part typed then, once
        assertEquals("今天天气不错，" to "我们出去走", talk.step("今天天气不错，我们出去走", 7))
    }

    @Test
    fun aPolishFailingStopsTypingAsItGoes() {
        val talk = VoiceTalk(asItGoes = true)
        assertEquals("今天天气不错，" to "我们", talk.step("今天天气不错，我们", 7))
        // the raw text instead, the 嗯 the polish took out back in: nothing more typed as it goes
        val step = talk.heard(VoiceLive.Heard("嗯今天天气不错，我们出去。走吧", 0, fallback = true))
        assertEquals("", step.type)
        // what composes is what follows the part typed, found in the raw text
        assertEquals("我们出去。走吧", step.composing)
        assertEquals("", talk.step("今天天气不错，我们出去。走吧", 14).first)
    }

    @Test
    fun thePolishFailingAtTheEndTheRawTextAfterWhatWasTypedIsTyped() {
        val talk = VoiceTalk(asItGoes = true)
        assertEquals("我到了。" to "马上", talk.step("我到了。马上", 4))
        // polished 「我到了。」 typed; the end's polish failed: the raw text, its 嗯 back, no marks
        assertEquals("马上走", talk.rest("嗯我到了马上走", raw = true))
        // as heard last, the talk cut short
        talk.heard(VoiceLive.Heard("嗯我到了马上走", 0, fallback = true))
        assertEquals("马上走", talk.restNow())
        // not raw: the polished text, after what was typed as it is
        assertEquals("马上走。", talk.rest("我到了。马上走。"))
        // what was typed not all in it (it should be): what differs is typed, not dropped
        assertEquals("你好", talk.rest("你好", raw = true))
    }

    // review F5784, F6545, F6514: each form a number is written in, found in the raw text as said
    @Test
    @Uat("VO-29")
    fun thePolishFailingAfterANumberWrittenInDigitsTypesWhatFollowsItOnce() = assertRows(
        listOf(
            // typed polished, settled; the raw text when the polish failed; what is left to type
            Triple("我买了3个苹果。", "我买了三个苹果再见", "再见"),
            Triple("涨了50%。", "涨了百分之五十然后", "然后"),
            // a clock's 分 goes with it
            Triple("8:05到。", "八点五分到再见", "再见"),
            // 点 between two digits
            Triple("长12.5米。", "长十二点五米然后", "然后"),
            // a letter the polish cased otherwise
            Triple("V1.23版。", "v一点二三版然后", "然后"),
            // 万 and 亿 inside the number (F6514)
            Triple("共336005元。", "共三十三万六千零五元再见", "再见"),
            Triple("共100070000元。", "共一亿零七万元再见", "再见"),
            // a 万 written as a character stays one
            Triple("共5万元。", "共五万元再见", "再见"),
        ),
        { (typed, raw, _) -> "$typed then $raw" },
    ) { (typed, raw, left) ->
        val talk = VoiceTalk(asItGoes = true)
        assertEquals(typed to "", talk.step(typed, typed.length))
        assertEquals(left, talk.rest(raw, raw = true))
    }
}
