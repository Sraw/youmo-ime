/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.input.keyboard.CodeRowHeight
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the bar's clipboard suggestion shows of a long clip; how tall the code row above it is. */
class KawaiiBarComponentTest {

    @Test
    fun aClipIsCutAfter42CharactersAndNeverInsideAnEmoji() {
        assertEquals("a".repeat(42), clipboardPreview("a".repeat(50)))
        // the 42nd is an emoji, two UTF-16 units
        assertEquals("a".repeat(41) + "😀", clipboardPreview("a".repeat(41) + "😀b"))
        assertEquals("复制", clipboardPreview("复制"))
    }

    // the code row is tall enough to tap only in an input method showing the syllable strip: pinyin
    // on the 14 keys, 双拼 on the 18 or nine keys; thin in every other
    @Test
    fun theCodeRowIsTallOnlyInAnInputMethodWithTheStrip() {
        assertEquals(40, KawaiiBarComponent.MIN_TOUCH_DP)
        val tall = CodeRowHeight.STRIP_DP
        val thin = CodeRowHeight.THIN_DP
        assertRows(
            listOf(
                Triple(Engines.PINYIN, PinyinKeyboard.Keys14 to ShuangpinKeyboard.Full, tall) to "拼音 on the 14 keys",
                Triple(Engines.PINYIN, PinyinKeyboard.Full to ShuangpinKeyboard.Keys18, thin) to "拼音 全键盘, 双拼 on 18",
                Triple(Engines.SHUANGPIN, PinyinKeyboard.Full to ShuangpinKeyboard.Keys18, tall) to "双拼 on the 18 keys",
                Triple(Engines.SHUANGPIN, PinyinKeyboard.Full to ShuangpinKeyboard.NineKeys, tall) to "九键双拼",
                Triple(Engines.SHUANGPIN, PinyinKeyboard.Keys14 to ShuangpinKeyboard.Full, thin) to "双拼 全键盘, pinyin on 14",
                Triple(Engines.ENGLISH, PinyinKeyboard.Keys14 to ShuangpinKeyboard.Keys18, thin) to "English",
                Triple(Engines.T9, PinyinKeyboard.Keys14 to ShuangpinKeyboard.Keys18, thin) to "九键拼音",
            ),
            { it.second },
        ) { (row, _) -> assertEquals(row.third, KawaiiBarComponent.preeditHeight(row.first, row.second.first, row.second.second)) }
    }
}
