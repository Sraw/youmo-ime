/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SP-03: 微软 and 紫光 双拼 type ing on `;`, which the letters had no key for. */
class SemicolonKeyTest {

    @Test
    fun onlyTheSchemesThatTypeOnItShowIt() {
        assertTrue(semicolonKey(Engines.SHUANGPIN, ShuangpinScheme.MICROSOFT))
        assertTrue(semicolonKey(Engines.SHUANGPIN, ShuangpinScheme.ZIGUANG))
        listOf(
            ShuangpinScheme.ZIRANMA, ShuangpinScheme.ABC, ShuangpinScheme.ZHONGWENZHIXING,
            ShuangpinScheme.PINYINJIAJIA, ShuangpinScheme.XIAOHE, ShuangpinScheme.GB,
        ).forEach { assertFalse(semicolonKey(Engines.SHUANGPIN, it)) }
    }

    @Test
    fun fullPinyinAndEnglishNever() {
        assertFalse(semicolonKey(Engines.PINYIN, ShuangpinScheme.MICROSOFT))
        assertFalse(semicolonKey(Engines.ENGLISH, ShuangpinScheme.MICROSOFT))
    }
}
