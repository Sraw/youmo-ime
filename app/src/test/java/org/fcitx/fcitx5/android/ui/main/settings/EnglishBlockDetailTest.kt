/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// one all in lower case is blocked in any case, any other only as written
@RunWith(RobolectricTestRunner::class)
class EnglishBlockDetailTest {

    private val res = RuntimeEnvironment.getApplication().resources

    private fun detail(text: String) = PinyinWords(UserWord.Kind.BLOCKED).detail(res, UserWord(text, "", UserWord.Kind.BLOCKED))

    @Test
    fun aWordsCaseIsMatchedAsEnglishBlocksMatchesIt() {
        val any = res.getString(R.string.english_block_detail)
        val exact = res.getString(R.string.english_block_detail_exact)
        assertRows(
            "hello" to { assertEquals(any, detail("hello")) },
            "iPhone" to { assertEquals(exact, detail("iPhone")) },
            // a capital first only is still not all lower case: as written
            "Hello" to { assertEquals(exact, detail("Hello")) },
        )
    }
}
