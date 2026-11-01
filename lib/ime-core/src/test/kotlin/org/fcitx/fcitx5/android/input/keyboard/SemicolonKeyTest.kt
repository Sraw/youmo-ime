/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SP-03: 微软 双拼 types ing on `;`, which the letters had no key for. */
class SemicolonKeyTest {

    @Test
    fun onlyTheSchemesThatTypeOnItShowIt() {
        assertTrue(semicolonKey(InputMethodCatalog.SHUANGPIN, ShuangpinScheme.MICROSOFT))
        listOf(ShuangpinScheme.ZIRANMA, ShuangpinScheme.XIAOHE).forEach { assertFalse(semicolonKey(InputMethodCatalog.SHUANGPIN, it)) }
    }

    @Test
    fun fullPinyinAndEnglishNever() {
        assertFalse(semicolonKey(InputMethodCatalog.PINYIN, ShuangpinScheme.MICROSOFT))
        assertFalse(semicolonKey(InputMethodCatalog.ENGLISH, ShuangpinScheme.MICROSOFT))
    }
}
