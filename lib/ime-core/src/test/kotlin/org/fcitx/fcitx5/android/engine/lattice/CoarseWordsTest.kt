/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoarseWordsTest {

    @Test
    fun theListIsItsWordsAloneNoCommentsNorBlanks() {
        val list = CoarseWords.LIST
        assertTrue("小穴" in list && "fuck" in list)
        assertTrue(list.none { it.isBlank() || it.startsWith("#") || it != it.trim() })
        assertEquals(list.distinct(), list)
    }

    /** The words coarse.txt's header keeps out, typed on purpose or as common in another sense: ranked lower, they would get in the way. */
    @Test
    fun wordsTheHeaderKeepsOutAreNotListed() {
        val kept = listOf("卧槽", "妈的", "屌丝", "很屌", "屌", "阴道", "乳房", "卖淫", "色情", "porn", "杂种", "啪啪啪", "cum")
        assertEquals(emptyList<String>(), kept.filter { it in CoarseWords.LIST })
    }

    @Test
    fun wordsTheVocabularyLacksAreLeftOut() {
        val coarse = CoarseWords.of({ if (it == "b") 3 else -1 }, listOf("a", "b"))
        assertTrue(coarse.has(3))
        assertFalse(coarse.has(0))
        assertFalse(coarse.has(-1))
        assertFalse(CoarseWords.NONE.has(3))
    }

    /** The user's [counts] and the [texts] of their own words, telling how often it is asked for a count. */
    private class Typed(private val counts: Map<Int, Float>, private val texts: Map<Int, String>) : UserWords {
        var asked = 0
        override fun child(node: Int, syllable: Int) = -1
        override fun childCount(node: Int) = 0
        override fun wordCount(node: Int) = 0
        override fun word(node: Int, index: Int) = -1
        override fun text(word: Int) = texts.getValue(word)
        override fun count(word: Int): Float = counts.getOrDefault(word, 0f).also { asked++ }
    }

    // a vocabulary of 10, "b" its 3; past it the user's own 12, "c", and 13, "d"
    private val list = CoarseWords.of({ if (it == "b") 3 else -1 }, listOf("a", "b", "c"))
    private val texts = mapOf(12 to "c", 13 to "d")

    @Test
    fun aWordOfTheListIsUnaskedTillTheUserTypesIt() {
        assertTrue(list.unasked(3, null, 10))
        assertTrue(list.unasked(3, Typed(emptyMap(), texts), 10))
        assertTrue("the user's own, by its text", list.unasked(12, Typed(emptyMap(), texts), 10))
        assertFalse(list.unasked(3, Typed(mapOf(3 to 1f), texts), 10))
        assertFalse(list.unasked(12, Typed(mapOf(12 to 1f), texts), 10))
        assertFalse(list.unasked(0, null, 10))
        assertFalse(list.unasked(13, Typed(emptyMap(), texts), 10))
    }

    /**
     * Guards speed, not a result ([aWordOfTheListIsUnaskedTillTheUserTypesIt] has those): asked of every arc
     * of a lattice, nearly none of them the list's, unasked looks up the user's counts of the list's words alone.
     */
    @Test
    fun theUsersCountsAreLookedUpForTheListsWordsAlone() {
        val user = Typed(mapOf(0 to 1f, 13 to 1f), texts)
        assertFalse(list.unasked(0, user, 10))
        assertFalse(list.unasked(13, user, 10))
        assertEquals("counts looked up for words off the list", 0, user.asked)
        assertTrue(list.unasked(3, user, 10))
        assertEquals("counts looked up for a word of the list", 1, user.asked)
    }
}
