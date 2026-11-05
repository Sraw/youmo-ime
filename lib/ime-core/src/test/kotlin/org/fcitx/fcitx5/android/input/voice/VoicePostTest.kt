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

    @Test
    fun nothingHeardIsNothingTyped() {
        assertEquals("", VoicePost.text(emptyList()))
        assertEquals("", VoicePost.text(listOf(VoicePost.Stretch("咳咳。"))))
    }
}
