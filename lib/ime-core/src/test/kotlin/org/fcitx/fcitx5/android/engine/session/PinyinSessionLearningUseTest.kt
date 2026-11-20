/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Reset
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.user.LearningUse
import org.fcitx.fcitx5.android.engine.user.LearningUse.Counts
import org.junit.Assert.assertEquals
import org.junit.Test

/** [LearningUse]: the channels of what is learned taken away one by one, for ime-eval to measure; the default is the app's. */
class PinyinSessionLearningUseTest {

    private val data = sessionTestData()

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    // 拟 picked three times at ni: by the counts alone, the habits not ranking, it then goes before 你
    private fun learned(counts: Counts): PinyinSession {
        val learner = Learner(data)
        val learning = PinyinSession(data, PinyinSegmenter(), pageSize = 20, user = learner.model, prediction = false, learnTo = learner.learnTo())
        repeat(3) {
            learning.apply(Select(learning.type("ni").candidates.indexOf("拟")))
            learning.apply(Reset)
        }
        return PinyinSession(data, PinyinSegmenter(), pageSize = 20, user = learner.model, prediction = false, use = LearningUse(counts = counts))
    }

    private fun Session.first(keys: String) = type(keys).candidates.first().also { apply(Reset) }

    @Test
    fun theUsersCountsGoWhereTheyAreAskedTo() {
        val full = learned(Counts.FULL)
        assertEquals("拟", full.first("ni"))
        assertEquals("拟见", full.first("nijian"))
        val none = learned(Counts.NONE)
        assertEquals("你", none.first("ni"))
        assertEquals("你见", none.first("nijian"))
        val multi = learned(Counts.MULTI)
        assertEquals("你", multi.first("ni"))
        assertEquals("拟见", multi.first("nijian"))
    }

    @Test
    fun withCountsInSentencesOnlyAKeyTypedReadsAsAllTypedAtOnce() {
        // a word read from the start was scored as all of the input at the last key: scored again once it is not, and back
        val session = learned(Counts.MULTI)
        val keyByKey = session.type("nijian").candidates
        session.apply(Reset)
        assertEquals(session.typeAll("nijian").candidates, keyByKey)
        session.apply(Reset)
        session.type("nij")
        assertEquals(listOf("你"), session.apply(Action.Backspace).candidates.take(1))
    }
}
