/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.data.prefs.PinyinKeyboard
import org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard
import org.fcitx.fcitx5.android.engine.assertRows
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

    // the code row is a touch tall (MIN_TOUCH_DP, 40) when a key-picked syllable lives there: with
    // the 14 keys or 双拼's 18; then for every input method, so the keyboard never jumps between them
    @Test
    fun theCodeRowIsATouchTallWithTheFourteenOrEighteenKeys() {
        assertEquals(40, KawaiiBarComponent.MIN_TOUCH_DP)
        assertEquals(18, KawaiiBarComponent.PREEDIT_HEIGHT)
        assertRows(
            listOf(
                Triple(PinyinKeyboard.Keys14, ShuangpinKeyboard.Full, KawaiiBarComponent.MIN_TOUCH_DP) to "the 14 keys",
                Triple(PinyinKeyboard.Full, ShuangpinKeyboard.Keys18, KawaiiBarComponent.MIN_TOUCH_DP) to "双拼's 18 keys",
                Triple(PinyinKeyboard.Keys14, ShuangpinKeyboard.Keys18, KawaiiBarComponent.MIN_TOUCH_DP) to "both",
                Triple(PinyinKeyboard.Keys14, ShuangpinKeyboard.NineKeys, KawaiiBarComponent.MIN_TOUCH_DP) to "the 14 keys, whatever 双拼's",
                Triple(PinyinKeyboard.Full, ShuangpinKeyboard.NineKeys, KawaiiBarComponent.PREEDIT_HEIGHT) to "九键双拼's syllables are its column's",
                Triple(PinyinKeyboard.Full, ShuangpinKeyboard.Full, KawaiiBarComponent.PREEDIT_HEIGHT) to "no syllables to pick: the thin row",
            ),
            { it.second },
        ) { (row, _) -> assertEquals(row.third, KawaiiBarComponent.preeditHeight(row.first, row.second)) }
    }
}
