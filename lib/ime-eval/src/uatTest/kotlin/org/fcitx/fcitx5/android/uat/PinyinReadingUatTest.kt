/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** uat/06-pinyin.md: the readings above candidates, on the shipped data (PinyinUatTest is at detekt's size). */
class PinyinReadingUatTest {

    // 䏝 has no tone under chun in the tone table: pinyin shows it toneless, zhuyin as tone 1
    @Test
    @Uat("PY-121")
    fun aCharacterOfNoKnownToneReadsToneless() {
        fun reading(style: ReadingStyle, text: String) = with(EngineReplay(settings(EngineSettings(candidateReading = style)))) {
            type("chun")
            expectAmong(text)
            readingOf(text)
        }
        assertRows(
            "pinyin, 䏝: no tone known under chun, the typed syllable toneless" to { assertEquals("chun", reading(ReadingStyle.PINYIN, "䏝")) },
            "pinyin, 春: its own tone, as before" to { assertEquals("chūn", reading(ReadingStyle.PINYIN, "春")) },
            "zhuyin, 䏝: none, a toneless ㄔㄨㄣ would read as tone 1" to { assertEquals("", reading(ReadingStyle.ZHUYIN, "䏝")) },
            "zhuyin, 春: its own tone (tone 1, unmarked)" to { assertEquals("ㄔㄨㄣ", reading(ReadingStyle.ZHUYIN, "春")) },
        )
    }
}
