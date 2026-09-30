/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExactReadingTest {

    private fun read(input: String, expected: String, keep: (Int) -> Boolean = { true }) =
        ExactReading.of(Sample(input, expected, "daily"), keep)?.map { "${Syllables.spelling(it.syllable)}@${it.at}+${it.length}" }

    @Test
    fun eachSyllableSaysWhereItWasTyped() {
        assertEquals(listOf("zhong@0+5", "guo@5+3"), read("zhongguo", "中国"))
        // separators are skipped, and not counted in a syllable's length
        assertEquals(listOf("xi@0+2", "an@3+2"), read("xi'an", "西安"))
        assertEquals(listOf("fang@0+4", "an@5+2"), read("fang'an", "方案"))
        assertEquals(listOf("xi@0+2", "an@4+2"), read("xi''an", "西安"))
        assertEquals(listOf("xian@1+4"), read("'xian", "先"))
    }

    @Test
    fun onlyOneCutWillDo() {
        // xi an and xia n
        assertNull(read("xian", "西安"))
        // unless one is not allowed
        assertEquals(listOf("xi@0+2", "an@2+2"), read("xian", "西安") { Syllables.spelling(it) != "n" })
        // not one syllable per character
        assertNull(read("xian", "先生"))
        // not exact: an abbreviation
        assertNull(read("nh", "你好"))
    }
}
