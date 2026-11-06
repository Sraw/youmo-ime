/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals("订了CEP的票，hello", live.heard("hello"))
        assertEquals("订了CEP的票，hello world", live.heard("hello world"))
        live.closed("hello world")
        assertEquals("订了CEP的票，hello world，2026年", live.heard("二零二六年"))
        assertEquals("订了CEP的票，hello world，2026年", live.closed("二零二六年"))
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
        assertEquals("我到", live.heard("嗯我到"))
        assertEquals("我到了。马上走", live.heard("嗯我到了马上走"))
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
}
