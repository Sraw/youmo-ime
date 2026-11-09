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

// a blocked English word (no reading) says how its case is matched, as EnglishBlocks matches it: one all in
// lower case in any case, any other only as written (R20 F6404)
@RunWith(RobolectricTestRunner::class)
class EnglishBlockDetailTest {

    private val res = RuntimeEnvironment.getApplication().resources

    private fun detail(text: String) = PinyinWords(UserWord.Kind.BLOCKED).detail(res, UserWord(text, "", UserWord.Kind.BLOCKED))

    @Test
    fun aWordsCaseIsMatchedAsEnglishBlocksMatchesIt() {
        val any = res.getString(R.string.english_block_detail)
        val exact = res.getString(R.string.english_block_detail_exact)
        assertRows(
            // all in lower case: blocked in any case (Hello, HELLO too)
            "hello" to { assertEquals(any, detail("hello")) },
            // a capital inside: only as written
            "iPhone" to { assertEquals(exact, detail("iPhone")) },
            // a capital first only is still not all lower case (text == text.lowercase()): as written
            "Hello" to { assertEquals(exact, detail("Hello")) },
        )
    }
}
