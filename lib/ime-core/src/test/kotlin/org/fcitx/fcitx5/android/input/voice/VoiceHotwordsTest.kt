/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.engine.runtime.UserWord.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceHotwordsTest {

    // a read's ticket, as VoiceEngine gets one: [VoiceHotwords.Remembered.shared] launching it
    private fun VoiceHotwords.Remembered.ticket(): Long = shared<Long>({ false }) { it }

    @Test
    fun spelledACharacterAToken() {
        assertEquals("佛 系", HotwordSpelling.spelled("佛系"))
        assertEquals("幽 默 输 入 法", HotwordSpelling.spelled("幽默输入法"))
    }

    @Test
    fun onlyChineseOfTwoCharactersAndMore() {
        assertNull(HotwordSpelling.spelled("佛"))
        assertNull(HotwordSpelling.spelled(""))
        assertNull(HotwordSpelling.spelled("YYDS"))
        assertNull(HotwordSpelling.spelled("A股"))
        assertNull(HotwordSpelling.spelled("你好！"))
        assertNull(HotwordSpelling.spelled("一二三四五六七八九十一二三"))
        // 𠮶仔: a character beyond the BMP
        assertNull(HotwordSpelling.spelled("\uD842\uDFB6仔"))
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
        // habits off: nothing of theirs listened for, the blocked never heard; so too words read before it went off
        assertEquals(VoiceHotwords.Words("", "尬 聊/内 卷"), VoiceHotwords.ofUser(words).withHabits(false))
        assertEquals(VoiceHotwords.ofUser(words), VoiceHotwords.ofUser(words).withHabits(true))
    }

    // 记住 in a table keeps the word to the table: the code is no reading, and voice uses none of them
    @Test
    fun aWordATableRememberedIsNoHotword() {
        val words = listOf(
            UserWord("经验", "xcgw", Kind.LEARNED, table = "wbx"),
            UserWord("幽默", "xcgw", Kind.LEARNED, table = "wbx"),
            UserWord("幽默", "you mo", Kind.LEARNED, 2f),
        )
        assertEquals("pinyin's own word of the same text still listened for", VoiceHotwords.Words("幽 默", ""), VoiceHotwords.ofUser(words))
        assertEquals(VoiceHotwords.Words.NONE, VoiceHotwords.ofUser(words.take(1)))
    }

    // 生僻字屏蔽: the engine's answer for the words and the recognizer's characters
    @Test
    fun aWordWithACharacterHeldBackIsNoHotwordAndTheCharactersAreNeverCompleted() {
        val words = listOf(
            UserWord("大龘", "da da", Kind.ADDED),
            UserWord("佛系", "fo xi", Kind.LEARNED, 3f),
            UserWord("龘龘", "da da", Kind.BLOCKED),
        )
        // the recognizer's characters 大, 龘 and 𤝰; 䶮 a word of the user's alone, held, no token of its
        val got = VoiceHotwords.ofUser(words, held = setOf("大龘", "龘", "𤝰", "龘龘", "䶮"), chars = listOf("大", "龘", "𤝰"))
        assertRows(
            "the added word with 龘 no hotword, the other one" to { assertEquals("佛 系", got.hotwords) },
            "a blocked word with it still blocked" to { assertEquals("龘 龘", got.blocked) },
            "the recognizer's characters held, not the words, nor a character it has not (review S6)" to {
                assertEquals(setOf("龘", "𤝰"), got.held)
            },
            "the search told the blocked words, then each character a token of its own" to { assertEquals("龘 龘/龘/𤝰", got.searchBlocked) },
            "kept with the habits off" to { assertEquals(got.held, got.withHabits(false).held) },
            "what is typed asked of them" to {
                assertTrue(got.holds("大龘了"))
                assertFalse(got.holds("大大"))
            },
            "none held: the search told the blocked words alone" to { assertEquals("龘 龘", VoiceHotwords.ofUser(words).searchBlocked) },
            "the words the homophones are made of: the one with 龘 left out, the blocked kept" to {
                assertEquals(listOf("佛系", "龘龘"), VoiceHotwords.unheld(words, setOf("大龘", "龘龘")).map { it.text })
            },
        )
    }

    @Test
    fun atMostSoMany() {
        val words = (0 until VoiceHotwords.MAX_USER + 10).map { UserWord("词" + ('一' + it), "", Kind.LEARNED) }
        assertEquals(VoiceHotwords.MAX_USER, VoiceHotwords.ofUser(words).hotwords.split('/').size)
    }

    @Test
    fun aListenerTheReadCameTooLateForUsesTheLastReadAndTheLateOneIsKeptForTheNext() {
        // an engine too busy to answer in time leaves the words last read in use, never none
        val remembered = VoiceHotwords.Remembered()
        val first = VoiceHotwords.Words("澜 境", "天 气")
        assertEquals(first, remembered.inTime(remembered.read(remembered.ticket(), first)))
        // the next read comes late: this listener has the first words
        val late = remembered.ticket()
        assertEquals(first, remembered.inTime(null))
        val added = VoiceHotwords.Words("澜 境 花 园", "天 气")
        remembered.read(late, added)
        // the next listener, the late read's
        assertEquals(added, remembered.inTime(null))
    }

    @Test
    fun aReadStartedEarlierButDoneLaterDoesNotReplaceANewerOne() {
        val remembered = VoiceHotwords.Remembered()
        val older = remembered.ticket()
        val newer = remembered.ticket()
        val fresh = VoiceHotwords.Words("花 园", "")
        remembered.read(newer, fresh)
        remembered.read(older, VoiceHotwords.Words.NONE)
        assertEquals(fresh, remembered.last)
    }

    @Test
    fun aReadStillUnderWayIsSharedAndOneDoneGivesWayToANewOne() {
        // holds while a slow read runs wait on it; once it is done the next hold reads again
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
