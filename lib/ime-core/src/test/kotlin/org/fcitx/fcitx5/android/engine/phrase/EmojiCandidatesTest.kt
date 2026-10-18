/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

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
}
