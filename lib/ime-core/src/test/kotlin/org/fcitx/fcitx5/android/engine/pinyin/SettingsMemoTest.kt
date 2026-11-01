/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The segmenters' tables made once for their settings and shared (S15 N2): never another's, never stale. */
class SettingsMemoTest {

    @Test
    fun eachKeyIsMadeOnceWhileItIsAmongTheLastAskedFor() {
        val made = ArrayList<String>()
        val memo = SettingsMemo<String, StringBuilder>(2) { made += it; StringBuilder(it) }
        val a = memo["a"]
        assertSame(a, memo["a"])
        memo["b"]
        // a asked for again: b is the one longest unasked when c comes
        assertSame(a, memo["a"])
        memo["c"]
        assertEquals(2, memo.size)
        assertSame(a, memo["a"])
        assertEquals(listOf("a", "b", "c"), made)
        // b went: made again, another
        memo["b"]
        assertEquals(listOf("a", "b", "c", "b"), made)
        assertEquals(2, memo.size)
    }

    @Test
    fun aSpellingIndexIsTheSettingsOwn() {
        val fuzzy = mutableSetOf(Fuzzy.U_OU)
        val index = SpellingIndex.of(fuzzy, typos = true)
        assertSame(index, SpellingIndex.of(setOf(Fuzzy.U_OU), typos = true))
        assertNotSame(index, SpellingIndex.of(setOf(Fuzzy.U_OU), typos = false))
        assertNotSame(index, SpellingIndex.of(setOf(Fuzzy.U_OU), typos = true, neighbours = true))
        // the key is a copy: the caller's set changed after makes no other settings' index this one
        fuzzy.clear()
        assertNotSame(index, SpellingIndex.of(emptySet(), typos = true))
        assertSame(index, SpellingIndex.of(setOf(Fuzzy.U_OU), typos = true))
        assertTrue(SpellingIndex.held <= SpellingIndex.MEMO)
    }

    // a segmenter over shared tables reads as one that made its own did: the same graph, whatever was made between
    @Test
    fun segmentersOfTheSameSettingsReadAlike() {
        val input = "nihaozhongguo"
        val first = PinyinSegmenter(setOf(Fuzzy.U_OU), typos = true, neighbours = true).segment(input).toString()
        // others asked for between, more than are kept: made anew, the same
        for (f in Fuzzy.entries.take(SpellingIndex.MEMO + 1)) PinyinSegmenter(setOf(f)).segment(input)
        assertEquals(first, PinyinSegmenter(setOf(Fuzzy.U_OU), typos = true, neighbours = true).segment(input).toString())

        // f on 2: 2 starts feng there, not on the phone's keys
        val custom = NineKeyLetters.parse("abcf,de,ghi,jkl,mno,pqrs,tuv,wxyz")
        val digits = "2364"
        val t9 = T9Segmenter(letters = custom).segment(digits).toString()
        val standard = T9Segmenter().segment(digits).toString()
        assertTrue(t9 != standard)
        for (f in Fuzzy.entries.take(SpellingIndex.MEMO + 1)) T9Segmenter(setOf(f)).segment(digits)
        assertEquals(t9, T9Segmenter(letters = custom).segment(digits).toString())
        assertEquals(standard, T9Segmenter().segment(digits).toString())
        assertTrue(T9Segmenter.held <= SpellingIndex.MEMO)
    }
}
