/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandTest {

    @Test
    fun aCommandSaidAlone() {
        assertEquals(VoiceCommand.Mark('。'), VoiceCommand.of("句号。"))
        assertEquals(VoiceCommand.Mark('？'), VoiceCommand.of("问号"))
        assertEquals(VoiceCommand.Mark('！'), VoiceCommand.of("感叹号！"))
        assertEquals(VoiceCommand.Lines(1), VoiceCommand.of("换行。"))
        assertEquals(VoiceCommand.Lines(1), VoiceCommand.of("请换行"))
        assertEquals(VoiceCommand.Lines(2), VoiceCommand.of("另起一段，"))
        assertEquals(VoiceCommand.Mark('，'), VoiceCommand.of("来个逗号"))
        assertEquals(VoiceCommand.Delete, VoiceCommand.of("删掉。"))
    }

    @Test
    fun saidInASentenceItIsTheWords() {
        for (said in listOf("我用句号结尾。", "换行符", "删掉这个文件", "句号和逗号", "请", "", "。", "撤销订单", "回车键", "不要删掉", "我说删掉"))
            assertNull(said, VoiceCommand.of(said))
    }
}
