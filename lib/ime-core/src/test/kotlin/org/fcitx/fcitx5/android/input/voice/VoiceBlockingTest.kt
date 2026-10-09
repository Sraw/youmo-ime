/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceBlockingTest {

    private val blocked = listOf("开饭")

    @Test
    fun onlyThoseWithTheWordAreSplit() {
        assertEquals(listOf("开饭了"), VoiceBlocking.suspects(listOf("开放了", "开饭了", "开饭了"), blocked))
        assertEquals(emptyList<String>(), VoiceBlocking.suspects(listOf("开放了"), blocked))
    }

    @Test
    fun theWordStandingAloneIsBlocked() {
        val nbest = listOf("开饭了", "开放了")
        val split = mapOf("开饭了" to intArrayOf(0, 2, 3))
        assertEquals(1, VoiceBlocking.pick(nbest, blocked, split))
    }

    @Test
    fun withinALongerWordOrAcrossTwoItIsNot() {
        // 开饭店 one word; 打开 饭盒 two, the blocked word across them
        assertEquals(0, VoiceBlocking.pick(listOf("开饭店"), blocked, mapOf("开饭店" to intArrayOf(0, 3))))
        assertEquals(0, VoiceBlocking.pick(listOf("打开饭盒"), blocked, mapOf("打开饭盒" to intArrayOf(0, 2, 4))))
    }

    @Test
    fun aWordOfCharsOfTheirOwnStandsToo() {
        // a blocked word the model lacks splits into its chars: still where words start and end
        assertEquals(1, VoiceBlocking.pick(listOf("快开饭", "快开放"), blocked, mapOf("快开饭" to intArrayOf(0, 1, 2, 3))))
    }

    @Test
    fun anyOccurrenceStandingCounts() {
        val text = "开饭店开饭"
        assertNull(VoiceBlocking.pick(listOf(text), blocked, mapOf(text to intArrayOf(0, 3, 5))))
    }

    @Test
    fun eachHasItOrCannotBeSplit() {
        assertNull(VoiceBlocking.pick(listOf("开饭", "开饭。"), blocked, mapOf("开饭" to intArrayOf(0, 2), "开饭。" to intArrayOf(0, 2, 3))))
        assertNull(VoiceBlocking.pick(listOf("开饭了"), blocked, emptyMap()))
        assertEquals(0, VoiceBlocking.pick(listOf("好的"), blocked, emptyMap()))
    }

    @Test
    fun asTypedWithoutTheRecognizersSpaces() {
        // X-ASR spaces Chinese as it does English, and 开 饭 is typed 开饭
        assertEquals("开饭了", VoiceText.clean("开 饭了"))
        assertEquals(listOf("开饭了"), VoiceBlocking.suspects(listOf("开 饭了", "开饭 了"), blocked))
        assertEquals(1, VoiceBlocking.pick(listOf("开 饭了", "开放了"), blocked, mapOf("开饭了" to intArrayOf(0, 2, 3))))
    }

    @Test
    fun theBlockedWordsAsWritten() {
        assertEquals(listOf("开饭", "内卷"), VoiceHotwords.Words("", "开 饭/内 卷").blockedWords)
        assertEquals(emptyList<String>(), VoiceHotwords.Words.NONE.blockedWords)
    }
}
