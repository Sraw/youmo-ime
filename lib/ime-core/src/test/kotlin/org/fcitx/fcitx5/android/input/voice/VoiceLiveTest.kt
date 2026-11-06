/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLiveTest {

    private val tags = VoicePolishTags(listOf("keep", "del"), listOf("", "，", "。"))

    // 嗯 deleted, a 。 after 了: a tagger as small as the tests need
    private val tagger = VoicePolishStream.Tagger { w ->
        IntArray(w.size) { if (w[it] == '嗯'.code) VoicePolishTags.DEL else VoicePolishTags.KEEP } to
            IntArray(w.size) { if (w[it] == '了'.code) 2 else 0 }
    }

    @Test
    fun segmentsJoinedWithAPauseMarkWhereTheyHaveNone() {
        val live = VoiceLive()
        live.closed("今天天气不错")
        live.closed("我们出去走走。")
        assertEquals("今天天气不错，我们出去走走。", live.raw)
        assertEquals("今天天气不错，我们出去走走。", live.finish())
    }

    @Test
    fun aSegmentStartingWithAMarkEndsTheOneBeforeWithIt() {
        val live = VoiceLive()
        live.closed("然后就教我们")
        live.closed("。然后那会")
        live.closed("，还小")
        assertEquals("然后就教我们。然后那会，还小", live.raw)
        val marked = VoiceLive()
        marked.closed("可以直接付款，")
        marked.closed("。而且")
        assertEquals("可以直接付款。而且", marked.raw)
    }

    @Test
    fun theSegmentBeingSaidShowsAfterTheClosedOnesAndIsReplacedAsItGrows() {
        val live = VoiceLive()
        live.closed("订了 C E P 的票，")
        assertEquals("订了CEP的票，hello", live.heard("hello").text)
        assertEquals("订了CEP的票，hello world", live.heard("hello world").text)
        live.closed("hello world")
        assertEquals("订了CEP的票，hello world，2026年", live.heard("二零二六年").text)
        assertEquals("订了CEP的票，hello world，2026年", live.closed("二零二六年").text)
    }

    @Test
    fun englishWordsAcrossSegmentsKeepASpace() {
        assertEquals("hello world", VoiceLive.join("hello", "world"))
        assertEquals("你好，world", VoiceLive.join("你好", "world"))
        assertEquals("hello。你好", VoiceLive.join("hello。", "你好"))
        assertEquals("你好", VoiceLive.join("", "你好"))
        assertEquals("你好", VoiceLive.join("你好", ""))
    }

    @Test
    fun aSegmentOfASoundOnlyIsLeftOut() {
        val live = VoiceLive()
        live.closed("嗯。")
        live.closed("咳咳。")
        assertEquals("", live.raw)
        assertNull(live.finish())
        live.closed("好的")
        assertEquals("好的", live.finish())
    }

    @Test
    fun theSegmentBeingSaidWhenItEndsIsTyped() {
        val live = VoiceLive()
        live.closed("我们走吧，")
        live.heard("马上")
        assertEquals("我们走吧，马上", live.finish())
    }

    @Test
    fun polishedAsItStreamsTheRulesAfter() {
        val live = VoiceLive(VoicePolishStream(tags, tagger, freezeAfter = 2, window = 8))
        // 嗯 frozen (two follow it): deleted; the rest as heard
        assertEquals("我到", live.heard("嗯我到").text)
        assertEquals("我到了。马上走", live.heard("嗯我到了马上走").text)
        live.heard("嗯我到了二零二六年")
        // the numbers in digits once polished, the tagger reading them as said
        assertEquals("我到了。2026年", live.finish())
    }

    @Test
    fun englishWordsKeepTheirSpacePolished() {
        val keep = VoicePolishStream.Tagger { w -> IntArray(w.size) to IntArray(w.size) }
        val live = VoiceLive(VoicePolishStream(tags, keep, freezeAfter = 2, window = 8))
        live.closed("我用 Google Maps 找")
        assertEquals("我用Google Maps找", live.finish())
    }

    private fun VoiceLive.Heard.settledText() = whole.substring(0, settled)

    @Test
    fun settledIsTheClosedSegmentsUpToAMarkWithMoreAfterIt() {
        val live = VoiceLive()
        // the segment being said may change anywhere: nothing of it settled
        assertEquals(0, live.heard("今天天气不错，我们").settled)
        // closed: up to its last mark with something after it
        assertEquals("今天天气不错，", live.closed("今天天气不错，我们出去").settledText())
        assertEquals("今天天气不错，", live.heard("走走").settledText())
        // a closed segment's own end mark is not: the next one may bring its own
        val end = VoiceLive()
        assertEquals(0, end.closed("今天天气不错。").settled)
        assertEquals(0, end.heard("我们").settled)
        assertEquals("今天天气不错。", end.closed("我们").settledText())
    }

    @Test
    fun settledPolishedIsTheFrozenPartOnly() {
        val live = VoiceLive(VoicePolishStream(tags, tagger, freezeAfter = 2, window = 8))
        val heard = live.closed("嗯我到了马上走")
        assertEquals("我到了。马上走", heard.whole)
        // 「我到了。马」 frozen (two after it): up to its mark
        assertEquals("我到了。", heard.settledText())
    }

    // a tagger writing 「，」 after 到 and keeping a 。 the recognizer wrote: 「，。」 is 「。」 once both are polished
    @Test
    fun aPauseMarkAMarkMayTakeAwayIsNotSettledAtTheEnd() {
        val comma = VoicePolishStream.Tagger { w -> IntArray(w.size) to IntArray(w.size) { if (w[it] == '到'.code) 1 else 0 } }
        val live = VoiceLive(VoicePolishStream(tags, comma, freezeAfter = 1, window = 8))
        live.closed("我到")
        // the closed segment polished alone is 「我到，」; the next starts with the recognizer's 。:
        // not typed up to the 「，」, which goes
        val heard = live.heard("。走吧")
        assertEquals("我到。走吧", heard.whole)
        assertEquals(0, heard.settled)
        val more = live.closed("。走吧")
        assertEquals("我到。", more.settledText())
        assertTrue(live.finished().startsWith(more.settledText()))
    }

    @Test
    fun settledEndIsAfterAMarkFollowedByText() {
        assertEquals(0, VoiceLive.settledEnd(""))
        assertEquals(0, VoiceLive.settledEnd("你好，"))
        assertEquals(3, VoiceLive.settledEnd("你好，我"))
        assertEquals(4, VoiceLive.settledEnd("你好！？我"))
        // a space after it, a colon or a decimal point: not there
        assertEquals(0, VoiceLive.settledEnd("hello, world"))
        assertEquals(0, VoiceLive.settledEnd("如下：一"))
        assertEquals(0, VoiceLive.settledEnd("3.5"))
    }

    @Test
    fun nothingSettledWhereWhatShowsDoesNotStartWithWhatStays() {
        assertEquals(3, VoiceLive.settled("你好，我们", "你好，我"))
        // the frozen text and what shows out of step: nothing typed, rather than the wrong thing
        assertEquals(0, VoiceLive.settled("您好，我们", "你好，我"))
        assertEquals(0, VoiceLive.settled("你好", "你好，我"))
    }
}
