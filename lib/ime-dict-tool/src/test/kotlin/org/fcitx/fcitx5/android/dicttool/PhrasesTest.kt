/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhrasesTest {

    private val dictionary = setOf("洛阳", "贵阳", "洛川")

    private fun phrases(vararg candidates: Pair<String, Int>) =
        Phrases { text, from, to -> text.substring(from, to) in dictionary }.apply { for ((text, count) in candidates) saw(text, count) }

    @Test
    fun aFunctionCharacterAtAnEndOrDeAnywhereMakesAPhrase() {
        val p = phrases()
        assertTrue(p.isPhrase("好的事", 99))
        assertTrue(p.isPhrase("了好", 99))
        assertTrue(p.isPhrase("好吗", 99))
        assertTrue(p.isPhrase("好好了", 99))
        // a verb's 了 is typed with it; 和 and 有 end words too
        assertFalse(p.isPhrase("好了", 99))
        assertFalse(p.isPhrase("碳中和", 99))
        assertFalse(p.isPhrase("保有", 99))
    }

    @Test
    fun mostOfItsCountInsideOneLongerCandidateMakesAFragment() {
        val p = phrases("耗子好" to 9, "服务员" to 8)
        assertTrue(p.isPhrase("子好", 15))
        assertTrue(p.isPhrase("务员", 10))
        // half is not most
        assertFalse(p.isPhrase("子好", 18))
    }

    @Test
    fun wordsOfTheDictionaryRunningThroughItTogetherMakeAFragment() {
        // runs into 阳市 and out of 去洛: neither alone half of its count, both together more
        val p = phrases("洛阳市" to 6, "贵阳市" to 6, "去洛阳" to 6, "去洛川" to 6)
        assertTrue(p.isPhrase("阳市", 20))
        assertTrue(p.isPhrase("去洛", 20))
        assertFalse(p.isPhrase("阳市", 24))
        assertFalse(p.isPhrase("去洛", 24))
    }
}
