/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
import org.fcitx.fcitx5.android.engine.host.Engines.UserWord.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceHotwordsTest {

    @Test
    fun spelledACharacterAToken() {
        assertEquals("佛 系", VoiceHotwords.spelled("佛系"))
        assertEquals("幽 默 输 入 法", VoiceHotwords.spelled("幽默输入法"))
    }

    @Test
    fun onlyChineseOfTwoCharactersAndMore() {
        assertNull(VoiceHotwords.spelled("佛"))
        assertNull(VoiceHotwords.spelled(""))
        assertNull(VoiceHotwords.spelled("YYDS"))
        assertNull(VoiceHotwords.spelled("A股"))
        assertNull(VoiceHotwords.spelled("你好！"))
        assertNull(VoiceHotwords.spelled("一二三四五六七八九十一二三"))
        // 𠮶仔: a character beyond the BMP
        assertNull(VoiceHotwords.spelled("\uD842\uDFB6仔"))
    }

    @Test
    fun theUsersWordsListenedForAndThoseBlockedNever() {
        val words = listOf(
            UserWord("幽默", "you mo", Kind.ADDED),
            UserWord("佛系", "fo xi", Kind.LEARNED, 3f),
            UserWord("幽默", "you mo", Kind.LEARNED, 2f),
            UserWord("OK", "", Kind.ADDED),
            UserWord("尬聊", "ga liao", Kind.BLOCKED),
            UserWord("内卷", "nei juan", Kind.BLOCKED),
            UserWord("X", "", Kind.BLOCKED),
        )
        assertEquals(VoiceHotwords.Words("幽 默 :5.0/佛 系", "尬 聊/内 卷"), VoiceHotwords.ofUser(words))
        assertEquals(VoiceHotwords.Words.NONE, VoiceHotwords.ofUser(emptyList()))
    }

    @Test
    fun atMostSoMany() {
        val words = (0 until VoiceHotwords.MAX_USER + 10).map { UserWord("词" + ('一' + it), "", Kind.LEARNED) }
        assertEquals(VoiceHotwords.MAX_USER, VoiceHotwords.ofUser(words).hotwords.split('/').size)
    }

    @Test
    fun aListenerTheReadCameTooLateForUsesTheLastReadAndTheLateOneIsKeptForTheNext() {
        // VO-10/VO-14 (F2458, F2467): an engine too busy to answer in time leaves the words last read in use, never none
        val remembered = VoiceHotwords.Remembered()
        val first = VoiceHotwords.Words("澜 境", "天 气")
        assertEquals(first, remembered.inTime(remembered.read(remembered.start(), first)))
        // the next read comes late: this listener has the first words
        val late = remembered.start()
        assertEquals(first, remembered.inTime(null))
        val added = VoiceHotwords.Words("澜 境 花 园", "天 气")
        remembered.read(late, added)
        // the next listener, the late read's
        assertEquals(added, remembered.inTime(null))
    }

    @Test
    fun aReadStartedEarlierButDoneLaterDoesNotReplaceANewerOne() {
        val remembered = VoiceHotwords.Remembered()
        val older = remembered.start()
        val newer = remembered.start()
        val fresh = VoiceHotwords.Words("花 园", "")
        remembered.read(newer, fresh)
        remembered.read(older, VoiceHotwords.Words.NONE)
        assertEquals(fresh, remembered.last)
    }

    @Test
    fun aReadStillUnderWayIsSharedAndOneDoneGivesWayToANewOne() {
        // F2498: holds while a slow read runs wait on it; once it is done the next hold reads again
        val remembered = VoiceHotwords.Remembered()
        val launched = mutableListOf<Long>()
        var running = true
        val read = { remembered.shared<Long>({ running }) { ticket -> ticket.also { launched += it } } }
        assertEquals(1L, read())
        assertEquals(1L, read())
        assertEquals(1L, read())
        assertEquals(listOf(1L), launched)
        running = false
        assertEquals(2L, read())
        assertEquals(listOf(1L, 2L), launched)
    }
}
