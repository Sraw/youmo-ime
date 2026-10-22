/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.session.Action.Forget
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Reset
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the user committed, typed short (impl/design-1b.md D32, U-06, U-65): 简拼's first is the
 * model's alone, what was typed in full never moves it; a word of the user's own comes next to it,
 * before the dictionary's words not used there.
 */
class PinyinSessionLearnedTest {

    private val data = sessionTestData()
    private val learner = Learner(data)
    private val session = PinyinSession(data, PinyinSegmenter(), pageSize = 20, prediction = false, user = learner.model, learnTo = learner.learnTo())

    private fun type(keys: String): Snapshot = keys.map { session.apply(Key(it)) }.last()

    private fun pick(keys: String, vararg texts: String): String {
        var shown = type(keys)
        for (text in texts) {
            val i = shown.candidates.indexOf(text)
            assertTrue("$text in ${shown.candidates}", i >= 0)
            shown = session.apply(Select(i))
        }
        return shown.commit.also { session.apply(Reset) }
    }

    private fun first(keys: String) = type(keys).candidates.take(2).also { session.apply(Reset) }

    @Test
    fun aWordPutTogetherTwiceComesNextToTheFirstForItsInitials() {
        val fresh = type("nh").candidates.also { session.apply(Reset) }
        assertEquals("你好", fresh[0])
        assertEquals("拟好", pick("nihao", "拟", "好"))
        // once may be a slip: not the user's word, nothing moved under nh
        assertEquals(fresh, type("nh").candidates.also { session.apply(Reset) })
        pick("nihao", "拟", "好")
        assertEquals(listOf("你好", "拟好"), first("nh"))
    }

    @Test
    fun theFirstUnderInitialsStaysTheModelsAsTheWordsAreUsed() {
        repeat(2) { pick("nihao", "拟", "好") }
        repeat(3) { pick("nihao", "拟好") }
        assertEquals(listOf("你好", "拟好"), first("nh"))
        repeat(3) { pick("nh", "你好") }
        assertEquals(listOf("你好", "拟好"), first("nh"))
    }

    // its initial alone would cut sentences into the characters typed most
    @Test
    fun aCharacterIsNotLiftedUnderItsInitial() {
        val first = first("n")
        repeat(2) { pick("ni", "拟") }
        assertEquals(first, first("n"))
    }

    @Test
    fun aWordForgottenIsUnderItsInitialsNoLonger() {
        val fresh = type("nh").candidates.also { session.apply(Reset) }
        repeat(2) { pick("nihao", "拟", "好") }
        val shown = type("nh")
        assertEquals("拟好", shown.candidates[1])
        session.apply(Forget(1))
        session.apply(Reset)
        assertEquals(fresh, type("nh").candidates)
    }
}
