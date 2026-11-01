/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.keyboard.CodeRowHeight
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
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

    // the code row is tall enough to tap only where the keyboard shows the syllable strip: pinyin on
    // the 14 or 18 keys, 双拼 on the 18, 14 or nine keys; thin on every other
    @Test
    fun theCodeRowIsTallOnlyOnAKeyboardWithTheStrip() {
        assertEquals(40, KawaiiBarComponent.MIN_TOUCH_DP)
        val tall = CodeRowHeight.STRIP_DP
        val thin = CodeRowHeight.THIN_DP
        val sp = ShuangpinScheme.ZIRANMA
        assertRows(
            listOf(
                Triple(InputMethodCatalog.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14), tall) to "拼音 on the 14 keys",
                Triple(InputMethodCatalog.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS18), tall) to "拼音 on the 18 keys",
                Triple(InputMethodCatalog.PINYIN, ImKeyboards.Chosen(pinyin = ImKeyboard.NINE_KEYS), thin) to "拼音 on the nine keys: its column",
                Triple(InputMethodCatalog.PINYIN, ImKeyboards.Chosen(shuangpin = ImKeyboard.KEYS18), thin) to "拼音 全键盘, 双拼 on 18",
                Triple(InputMethodCatalog.SHUANGPIN, ImKeyboards.Chosen(shuangpin = ImKeyboard.KEYS18), tall) to "双拼 on the 18 keys",
                Triple(InputMethodCatalog.SHUANGPIN, ImKeyboards.Chosen(shuangpin = ImKeyboard.KEYS14), tall) to "双拼 on the 14 keys",
                Triple(InputMethodCatalog.SHUANGPIN, ImKeyboards.Chosen(shuangpin = ImKeyboard.NINE_KEYS), tall) to "九键双拼",
                Triple(InputMethodCatalog.SHUANGPIN, ImKeyboards.Chosen(pinyin = ImKeyboard.KEYS14), thin) to "双拼 全键盘, pinyin on 14",
                Triple(InputMethodCatalog.ENGLISH, ImKeyboards.Chosen(ImKeyboard.KEYS14, ImKeyboard.KEYS18, ImKeyboard.NINE_KEYS), thin) to "English",
            ),
            { it.second },
        ) { (row, _) -> assertEquals(row.third, KawaiiBarComponent.preeditHeight(row.first, row.second, sp)) }
    }
}
