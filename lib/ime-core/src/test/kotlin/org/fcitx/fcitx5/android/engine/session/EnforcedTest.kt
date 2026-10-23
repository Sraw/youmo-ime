/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.lattice.Candidate
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the user's sentences keep put in the decoder's (U-08, impl/design-1c.md §1). */
class EnforcedTest {

    // word ids by text; wozhidao is wo(0..2) zhi dao(2..8), le(8..10)
    private val words = listOf("我", "知道", "指导", "了", "知道了", "老师", "喔")
    private fun id(text: String) = words.indexOf(text)
    private val text = { w: Int -> words[w] }

    private fun sentence(vararg parts: Pair<String, Int>, score: Float = -1f) = Candidate(
        parts.joinToString("") { it.first },
        parts.last().second,
        score,
        parts.map { if (it.first in words) id(it.first) else NO_WORD }.toIntArray(),
        parts.map { it.second }.toIntArray(),
    )

    private fun item(text: String, from: Int, to: Int, prev: String?, kind: Enforced.Kind) =
        Enforced.Item(from, to, intArrayOf(id(text)), intArrayOf(to), text, prev, kind)

    @Test
    fun aFirstAfterItsWordIsPutIn() {
        val s = listOf(sentence("我" to 2, "知道" to 8, score = -3f), sentence("喔" to 2, "知道" to 8))
        val out = Enforced.apply(s, listOf(item("指导", 2, 8, "我", Enforced.Kind.FIRST)), "", text)
        assertEquals(listOf("我指导", "喔知道"), out.map { it.text })
        // the score is the decoder's
        assertEquals(-3f, out[0].score)
        assertEquals(listOf(id("我"), id("指导")), out[0].words.toList())
    }

    @Test
    fun aFirstAtAStartFollowsTheWordBefore() = assertRows(
        // a first at the sentence's start follows what is before the input
        "a first at the start follows the word before" to {
            val s = listOf(sentence("知道" to 6))
            assertEquals("指导", Enforced.apply(s, listOf(item("指导", 0, 6, "我", Enforced.Kind.FIRST)), "我", text).single().text)
            assertEquals("知道", Enforced.apply(s, listOf(item("指导", 0, 6, "我", Enforced.Kind.FIRST)), "", text).single().text)
        },
        // F2814: input starting with a separator ('zhidao) starts the graph at 1, the sentence's first word there
        "a first at a start past a separator follows the word before" to {
            val s = listOf(sentence("知道" to 7))
            assertEquals("指导", Enforced.apply(s, listOf(item("指导", 1, 7, "我", Enforced.Kind.FIRST)), "我", text, start = 1).single().text)
            assertEquals("知道", Enforced.apply(s, listOf(item("指导", 1, 7, "我", Enforced.Kind.FIRST)), "", text, start = 1).single().text)
        },
    )

    @Test
    fun whatAnItemCannotCoverKeepsTheSentence() = assertRows(
        // 知道了 a word over zhidao and le: covered by a longer word, it stays
        "a word over the end keeps the sentence" to {
            val s = listOf(sentence("老师" to 6, "知道了" to 14))
            assertEquals("老师知道了", Enforced.apply(s, listOf(item("指导", 6, 12, null, Enforced.Kind.PIN)), "", text).single().text)
        },
        // the text rebuilt round text kept as typed: from the side that has none
        "text kept as typed stays" to {
            val s = listOf(sentence("x" to 1, "知道" to 7))
            assertEquals("x指导", Enforced.apply(s, listOf(item("指导", 1, 7, null, Enforced.Kind.PIN)), "", text).single().text)
        },
    )

    // review-1c N3: a first fits only after an item that stands: 老师 and 喔 overlap, so neither, and the first after 老师
    // takes no place from the word added by hand
    @Test
    fun aFirstFitsOnlyAfterAnItemThatStands() {
        val s = listOf(sentence("我" to 2, "知道" to 8, "了" to 10))
        val items = listOf(
            item("老师", 0, 8, null, Enforced.Kind.PIN), item("喔", 0, 2, null, Enforced.Kind.PIN),
            item("知道了", 8, 10, "老师", Enforced.Kind.FIRST), item("喔", 8, 10, null, Enforced.Kind.ADDED),
        )
        assertEquals("我知道喔", Enforced.apply(s, items, "", text).single().text)
    }

    @Test
    fun aPinAppliesAfterAnything() {
        val s = listOf(sentence("我" to 2, "知道" to 8, "了" to 10))
        assertEquals("我指导了", Enforced.apply(s, listOf(item("指导", 2, 8, null, Enforced.Kind.PIN)), "", text).single().text)
    }

    @Test
    fun sentencesTheSameOncePutInAreShownOnce() {
        val s = listOf(sentence("我" to 2, "知道" to 8), sentence("我" to 2, "指导" to 8))
        assertEquals(listOf("我指导"), Enforced.apply(s, listOf(item("指导", 2, 8, null, Enforced.Kind.PIN)), "", text).map { it.text })
    }

    @Test
    fun overlappingStretchesNeitherApply() {
        val a = item("指导", 2, 8, null, Enforced.Kind.PIN)
        val b = item("知道了", 4, 10, null, Enforced.Kind.PIN)
        assertEquals(emptyList<Enforced.Item>(), Enforced.settle(listOf(a, b)))
        val c = item("我", 0, 2, null, Enforced.Kind.PIN)
        assertEquals(setOf(c, a), Enforced.settle(listOf(a, c)).toSet())
    }

    // review-design-1c S5: a first whose word before is not there overlaps a pin, and the pin stands
    @Test
    fun aFirstThatDoesNotApplyLeavesAPinItOverlaps() {
        val s = listOf(sentence("我" to 2, "知道" to 8, "了" to 10))
        val pin = item("指导", 2, 8, null, Enforced.Kind.PIN)
        val first = Enforced.Item(2, 10, intArrayOf(id("知道了")), intArrayOf(10), "知道了", "喔", Enforced.Kind.FIRST)
        assertEquals("我指导了", Enforced.apply(s, listOf(pin, first), "", text).single().text)
    }

    @Test
    fun onOneStretchAPinBeatsAFirstBeatsAWordAdded() {
        val pin = item("指导", 2, 8, null, Enforced.Kind.PIN)
        val first = item("知道", 2, 8, "我", Enforced.Kind.FIRST)
        val added = item("知道", 2, 8, null, Enforced.Kind.ADDED)
        assertEquals(listOf(pin), Enforced.settle(listOf(added, first, pin)))
        assertEquals(listOf(first), Enforced.settle(listOf(added, first)))
    }
}
