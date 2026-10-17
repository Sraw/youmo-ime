/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.junit.Assert.assertEquals
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
}
