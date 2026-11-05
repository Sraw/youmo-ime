/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePostTest {

    @Test
    fun stretchesAreCleanedAndJoinedAsAHoldTypesThem() {
        val raw = listOf("嗯。", "订了 C E P 的票，", "hello", "world", "二零二六年")
        val hold = VoiceHold(cancelDistance = 100f)
        raw.forEach(hold::heard)
        // the hold does not tidy yet (C goes into the app later): the same without it
        assertEquals(hold.text, VoicePost.text(raw.map { VoicePost.Stretch(it) }, VoicePost.Layers(tidy = false)))
        assertEquals("订了CEP的票，hello world 2026年", VoicePost.text(raw.map { VoicePost.Stretch(it) }))
    }

    @Test
    fun aStammerAcrossTwoStretchesIsTidiedAsOne() {
        val raw = listOf("我们，", "我们去吧。").map { VoicePost.Stretch(it) }
        assertEquals("我们去吧。", VoicePost.text(raw))
        assertEquals("我们，我们去吧。", VoicePost.text(raw, VoicePost.Layers(tidy = false)))
    }

    private fun typed(vararg raw: String, layers: VoicePost.Layers = VoicePost.Layers()) =
        VoicePost.text(raw.map { VoicePost.Stretch(it) }, layers)

    @Test
    fun aCommandSaidAloneIsDone() {
        assertEquals("今天开会？", typed("今天开会，", "问号。"))
        assertEquals("第一条\n第二条。", typed("第一条，", "换行。", "第二条。"))
        assertEquals("第一段。\n\n第二段。", typed("第一段。", "另起一段。", "第二段。"))
        assertEquals("对的。", typed("对的。", "说错了重来，", "删掉。"))
        assertEquals("a mark said first", "。好的", typed("句号。", "好的"))
        assertEquals("nothing to take back", "好的", typed("删掉。", "好的"))
        assertEquals("said in a sentence, words", "我用句号结尾。", typed("我用句号结尾。"))
        assertEquals("off, the words", "今天开会，问号。", typed("今天开会，", "问号。", layers = VoicePost.Layers(commands = false)))
    }

    @Test
    fun deleteTakesBackTheLastThingSaid() {
        assertEquals("a mark, not the words it ended", "今天开会，", typed("今天开会，", "问号。", "删掉。"))
        assertEquals("今天开会？", typed("今天开会，", "问号。", "句号", "删掉"))
        assertEquals("a new line, its pause's mark back", "今天开会，", typed("今天开会，", "换行", "删掉"))
        assertEquals("第一段", typed("第一段", "换行", "第二段", "删掉", "删掉"))
        assertEquals("a filler alone said nothing to take back", "第一段", typed("第一段", "第二段", "嗯。", "删掉"))
        assertEquals("a command that changed nothing is what is taken back", "好吗？", typed("好吗？", "问号", "删掉"))
        assertEquals("\n", typed("换行", "逗号", "删掉"))
    }

    @Test
    fun aMarkAfterANewLineEndsTheLineBefore() {
        assertEquals("今天开会。\n", typed("今天开会，", "换行", "句号"))
        assertEquals("ignored with only new lines", "\n", typed("换行", "逗号"))
        assertEquals("the later in place of the earlier", "好的。", typed("好的", "问号", "句号"))
    }

    @Test
    fun aNewLineIsNoPlaceToTidyAcross() {
        assertEquals("我\n我觉得", typed("我", "换行", "我觉得"))
        assertEquals("好\n然后", typed("好", "换行", "嗯，然后"))
    }

    @Test
    fun nothingHeardIsNothingTyped() {
        assertEquals("", VoicePost.text(emptyList()))
        assertEquals("", VoicePost.text(listOf(VoicePost.Stretch("咳咳。"))))
    }
}
