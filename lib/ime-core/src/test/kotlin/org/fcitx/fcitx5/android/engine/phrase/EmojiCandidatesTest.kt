/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EmojiCandidatesTest {

    private val emoji = mapOf("哈哈" to listOf("😃", "😄", "😆"), "好" to listOf("👌"))

    private fun place(vararg candidates: String, whole: (String) -> Boolean = { true }) =
        EmojiCandidates.place(candidates.toList(), { emoji[it].orEmpty() }, { it }, whole) { it }

    @Test
    fun theFirstsEmojiGoAfterTheSecondAtMostTwo() {
        assertEquals(listOf("哈哈", "蛤蛤", "😃", "😄", "哈"), place("哈哈", "蛤蛤", "哈"))
        // fewer candidates: after them all
        assertEquals(listOf("哈哈", "😃", "😄"), place("哈哈"))
        // the second's are none
        assertEquals(listOf("蛤蛤", "哈哈", "哈"), place("蛤蛤", "哈哈", "哈"))
        assertEquals(emptyList<String>(), place())
    }

    @Test
    fun anEmojiListedAlreadyIsNotTwiceAndAWordOfTheStartOnlyHasNone() {
        assertEquals(listOf("哈哈", "😃", "😄", "😆"), place("哈哈", "😃"))
        assertEquals(listOf("好", "号"), place("好", "号") { it != "好" })
    }

    @Test
    fun theEmojiAddedAreToldAndThoseOfTheOneShownFirst() {
        val (listed, added) = EmojiCandidates.placed(listOf("蛤蛤", "哈哈"), "哈哈", { emoji[it].orEmpty() }, { it }, { true }) { it }
        assertEquals(listOf("蛤蛤", "哈哈", "😃", "😄"), listed)
        assertEquals(listOf("😃", "😄"), added)
        assertEquals(listOf("😄", "😆"), EmojiCandidates.pick("哈哈", { emoji[it].orEmpty() }) { it == "😃" })
    }

    @Test
    fun noneAddedTheCandidatesAreReturnedThemselves() {
        // PinyinSession.place reuses what it worked out for the list by this identity
        val read = listOf("号", "好")
        assertSame(read, EmojiCandidates.placed(read, "号", { emoji[it].orEmpty() }, { it }, { true }) { it }.first)
        assertSame(read, EmojiCandidates.placed(read, "好", { emoji[it].orEmpty() }, { it }, { false }) { it }.first)
        assertSame(read, AsideCandidates.place(read, "hao", { emoji[it].orEmpty() }, null, { it }, { true }, { it }).candidates)
    }

    @Test
    fun aWordLiftedToTheirPlaceKeepsItTheEmojiAfterIt() {
        val bird = mapOf("凤凰" to listOf("🐦"))
        fun placed(vararg candidates: String, on: Boolean = true, lifted: String? = "奉化") =
            EmojiCandidates.placed(candidates.toList(), candidates.first(), { if (on) bird[it].orEmpty() else emptyList() }, { it }, { true }, lifted = { it == lifted }) { it }.first
        assertRows(
            "lifted to third, emoji on: the word keeps third" to {
                assertEquals(listOf("凤凰", "丰厚", "奉化", "🐦", "峰会"), placed("凤凰", "丰厚", "奉化", "峰会"))
            },
            "no lifted word: the emoji third" to {
                assertEquals(listOf("凤凰", "丰厚", "🐦", "峰会"), placed("凤凰", "丰厚", "峰会", lifted = null))
            },
            "lifted word, no emoji for the first" to {
                assertEquals(listOf("丰厚", "凤凰", "奉化"), placed("丰厚", "凤凰", "奉化"))
            },
            "emoji off: the lifted word third, nothing added" to {
                assertEquals(listOf("凤凰", "丰厚", "奉化", "峰会"), placed("凤凰", "丰厚", "奉化", "峰会", on = false))
            },
            "a pin first: the lifted word fourth (docs/pinyin.md), the emoji after it" to {
                assertEquals(listOf("凤", "凤凰", "丰厚", "奉化", "🐦"), EmojiCandidates.placed(listOf("凤", "凤凰", "丰厚", "奉化"), "凤凰", { bird[it].orEmpty() }, { it }, { true }, lifted = { it == "奉化" }) { it }.first)
            },
            // HabitRanker's [DICTIONARY, FLOORED, FLOORED, LIFTED, …] (HabitRankerTest): no pin, but
            // the lifted word fourth all the same; with none lifted the emoji would be third, before them
            "floored words ahead: the lifted word fourth, the emoji fifth" to {
                assertEquals(listOf("凤凰", "丰厚", "峰会", "奉化", "🐦", "风华"), placed("凤凰", "丰厚", "峰会", "奉化", "风华"))
            },
            "three used twice (third to fifth): the emoji after all of them, not inside the run" to {
                assertEquals(listOf("凤凰", "丰厚", "奉化", "风华", "峰会", "🐦", "锋"),
                    EmojiCandidates.placed(listOf("凤凰", "丰厚", "奉化", "风华", "峰会", "锋"), "凤凰", { bird[it].orEmpty() }, { it }, { true }, lifted = { it in setOf("奉化", "风华", "峰会") }) { it }.first)
            },
            "a pin and two lifted (fourth, fifth): the emoji sixth" to {
                assertEquals(listOf("凤", "凤凰", "丰厚", "奉化", "风华", "🐦"),
                    EmojiCandidates.placed(listOf("凤", "凤凰", "丰厚", "奉化", "风华"), "凤凰", { bird[it].orEmpty() }, { it }, { true }, lifted = { it in setOf("奉化", "风华") }) { it }.first)
            },
            "a lifted word further down is not passed" to {
                assertEquals(listOf("凤凰", "丰厚", "🐦", "峰会", "风华", "奉化"), placed("凤凰", "丰厚", "峰会", "风华", "奉化"))
            },
        )
    }
}
