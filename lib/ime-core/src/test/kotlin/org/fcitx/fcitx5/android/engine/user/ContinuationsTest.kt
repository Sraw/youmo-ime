/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Continuations]: what is left of a whole phrase after the text before ends its start (U-73). */
class ContinuationsTest {

    private val c = Continuations().apply { set("你好呀亲爱的", 3, 5, LEAST) }

    // [before] split at [starts] (offsets of word starts), [last] the word put in last
    private fun rests(before: String, last: String, vararg starts: Int, limit: Int = Continuations.LIMIT, offered: (String) -> Boolean = { true }) =
        c.find(before, intArrayOf(*starts), last, limit, offered).map { it.rest }

    @Test
    fun afterEachStartTheRest() {
        assertEquals(listOf("好呀亲爱的"), rests("你", "你", 0))
        assertEquals(listOf("呀亲爱的"), rests("你好", "你好", 0))
        // picked 你 then 好: the text before is 你好 all the same
        assertEquals(listOf("呀亲爱的"), rests("你好", "好", 0, 1))
        assertEquals(listOf("亲爱的"), rests("你好呀", "呀", 0, 2))
    }

    @Test
    fun onlyFromAWordsStartCoveringTheLastWord() {
        // C51: the last word is the start, whatever is before it
        assertEquals(listOf("好呀亲爱的"), rests("谢谢你", "你", 0, 2))
        // 迷你 is the last word, not 你
        assertEquals(emptyList<String>(), rests("迷你", "迷你", 0))
        // a start inside the last word put in is none
        assertEquals(emptyList<String>(), rests("迷你", "迷你", 0, 1))
    }

    @Test
    fun aRestOfOneCharacterIsAWordsNotAPhrases() {
        assertEquals(emptyList<String>(), rests("你好呀亲爱", "亲爱", 0, 2, 3))
        assertEquals(emptyList<String>(), rests("你好呀亲爱的", "亲爱的", 0, 2, 3))
    }

    @Test
    fun theLastWordsOwnStartCountsWhereTheSplitPutItInAnother() {
        // the split had 谢你 as one word; 你 was put in last
        assertEquals(listOf("好呀亲爱的"), rests("谢你", "你", 0))
    }

    @Test
    fun mostUsedFirstThenLastUsedAtMostTheLimit() {
        c.set("你在哪里呀", 3, 9, LEAST)
        c.set("你吃了吗", 5, 1, LEAST)
        assertEquals(listOf("吃了吗", "在哪里呀"), rests("你", "你", 0))
        assertEquals(listOf("吃了吗", "在哪里呀", "好呀亲爱的"), rests("你", "你", 0, limit = 3))
        assertEquals(1, c.find("你", intArrayOf(0), "你", 1) { true }.size)
    }

    @Test
    fun notOfferedAndTooFewUsesAreNone() {
        assertEquals(emptyList<String>(), rests("你", "你", 0) { it != "你好呀亲爱的" })
        c.set("你好呀亲爱的", 2, 5, LEAST)
        assertNull(c.of("你好呀亲爱的"))
        assertTrue(c.isEmpty())
        c.set("你好呀亲爱的", 3, 5, LEAST)
        c.clear()
        assertTrue(c.isEmpty())
        assertEquals(emptyList<String>(), rests("", "", 0))
    }

    @Test
    fun aPhraseIsFoundOnceByItsLongestStart() {
        c.set("好呀好呀好的", 3, 5, LEAST)
        val found = c.find("好呀好呀", intArrayOf(0, 2), "好呀", 2) { true }
        assertEquals(listOf("好的"), found.map { it.rest })
        assertEquals("好呀好呀好的", found.single().whole)
        assertEquals(3, found.single().count)
    }

    // two phrases left with the same rest are one candidate: the one used most (review of 051-predict)
    @Test
    fun oneRestOnceTheMostUsedPhrase() {
        c.set("好呀亲爱的", 5, 1, LEAST)
        val found = c.find("你好呀", intArrayOf(0, 1, 2), "呀", 2) { true }
        assertEquals(listOf("亲爱的"), found.map { it.rest })
        assertEquals("好呀亲爱的", found.single().whole)
        c.set("好呀亲爱的", 3, 1, LEAST)
        // as many uses: the last used
        assertEquals("你好呀亲爱的", c.find("你好呀", intArrayOf(0, 1, 2), "呀", 2) { true }.single().whole)
    }

    // the tail was measured in UTF-16 units against a limit in code points: a character outside the
    // BMP counted twice, or cut in half
    @Test
    fun theTailIsAPhrasesLengthNoCharacterCut() {
        assertEquals("你好", Continuations.tail("你好"))
        val long = "一".repeat(20)
        assertEquals(12, Continuations.tail(long).length)
        // 𠀀 (two chars) one code point as Phrases counts: twelve in all, kept whole
        val ext = "𠀀" + "好".repeat(11)
        assertEquals(ext, Continuations.tail(ext))
        // thirteen: the first goes whole, never half of it
        assertEquals("好".repeat(12), Continuations.tail("𠀀" + "好".repeat(12)))
    }

    private companion object {
        const val LEAST = 3
    }
}
