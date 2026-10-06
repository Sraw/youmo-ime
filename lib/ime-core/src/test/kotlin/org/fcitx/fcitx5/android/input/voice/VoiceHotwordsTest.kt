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
        assertEquals(VoiceHotwords.Words("幽 默/佛 系", "尬 聊/内 卷"), VoiceHotwords.ofUser(words))
        assertEquals(VoiceHotwords.Words.NONE, VoiceHotwords.ofUser(emptyList()))
    }

    @Test
    fun atMostSoMany() {
        val words = (0 until VoiceHotwords.MAX_USER + 10).map { UserWord("词" + ('一' + it), "", Kind.LEARNED) }
        assertEquals(VoiceHotwords.MAX_USER, VoiceHotwords.ofUser(words).hotwords.split('/').size)
    }
}
