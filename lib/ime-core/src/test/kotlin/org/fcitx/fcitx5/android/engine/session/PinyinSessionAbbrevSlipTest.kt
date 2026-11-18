/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.pinyin.TouchBoxes
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Reset
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.Uat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双拼's two other readings of the keys together: a key read alone where it and the next make a
 * syllable, which reads only the words the user typed, and a key read as the one next to it by
 * where it was tapped, which reads the whole dictionary as a key hit does.
 */
class PinyinSessionAbbrevSlipTest {

    // 自然码: ni k w l is 你, kei (kw) and l, or 你看完了 by its initials; w tapped by e is ke
    private val data = pinyinDataOf(
        Triple("你", "ni", -2f), Triple("看", "kan", -2.5f), Triple("完", "wan", -2.5f), Triple("了", "le", -2f),
        Triple("你看完了", "ni kan wan le", -2.5f), Triple("课", "ke", -3.5f), Triple("剋", "kei", -4.5f),
        Triple("他", "ta", -2.5f), Triple("快", "kuai", -2f), Triple("可以", "ke yi", -3f), Triple("苦", "ku", -3f),
    )

    private val kind = Kinds.shuangpin("Ziranma")

    private fun session(learner: Learner) =
        PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA, neighbours = true), pageSize = 20, prediction = false, user = learner.model, learnTo = learner.learnTo(kind))

    private fun at(keys: String, slipped: Int = -1, dx: Float = 0f, dy: Float = 0f): List<Touch?> =
        keys.mapIndexed { i, c -> if (i == slipped) TouchBoxes.tap(TouchBoxes.QWERTY, c, dx = dx, dy = dy) else TouchBoxes.tap(TouchBoxes.QWERTY, c) }

    private fun Session.tap(keys: String, touches: List<Touch?>): List<String> = keys.indices.map { apply(Key(keys[it], touch = touches[it])) }.last().candidates.also { apply(Reset) }

    // a slip is no key read alone: with nothing typed it still reads the dictionary's words
    @Test
    fun withNothingTypedAKeyReadAsItsNeighbourReadsTheDictionary() {
        val s = session(Learner(data))
        // tq is tiu, which spells nothing: q tapped by a is ta
        assertEquals("他", s.tap("tq", at("tq", slipped = 1, dx = 0.3f, dy = 0.45f)).first())
        // and past a slip the words go on: w tapped by e, ni ke l
        val shown = s.tap("nikwl", at("nikwl", slipped = 3, dx = 0.42f))
        assertTrue("$shown", shown.any { it.startsWith("你课") })
    }

    @Test
    @Uat("SP-41")
    fun aWordTypedIsReadByItsInitialsBeforeASlipOfThem() {
        val learner = Learner(data)
        val s = session(learner)
        // never typed: no 你看完了 by n i, k, w, l, tapped where they may
        assertTrue("你看完了" !in s.tap("nikwl", at("nikwl")))
        assertTrue("你看完了" !in s.tap("nikwl", at("nikwl", slipped = 3, dx = 0.42f)))
        // typed in whole syllables once
        val whole = "nikjwjle"
        val typed = whole.indices.map { s.apply(Key(whole[it], touch = at(whole)[it])) }.last().candidates
        s.apply(Select(typed.indexOf("你看完了")))
        s.apply(Reset)
        // each key in its middle: by the initials, and no slip of them read
        val middle = s.tap("nikwl", at("nikwl"))
        assertEquals("$middle", "你看完了", middle.first())
        assertTrue("$middle", middle.none { it.startsWith("你课") })
        // w tapped by e: the slip is read, after the word typed
        val edge = s.tap("nikwl", at("nikwl", slipped = 3, dx = 0.42f))
        assertEquals("$edge", "你看完了", edge.first())
        assertTrue("$edge", edge.any { it.startsWith("你课") })
    }

    // a slip in a code read whole holds back only what the slip reads (061-sp-abbrev §3.6, review r2 M1): the words
    // typed and added by hand read by the keys alone are raised there all the same. ky is kuai; y tapped by u, ku
    @Test
    @Uat("SP-57")
    fun wordsTypedOrAddedAreRaisedByTheirInitialsBesideASlip() {
        val touches = at("ky", slipped = 1, dx = 0.45f)
        val learner = Learner(data)
        val fresh = session(learner).tap("ky", touches)
        assertTrue("$fresh", "可以" !in fresh && "苦" in fresh)
        // added by hand
        learner.model.listByHand(UserModel.Entry("可以", intArrayOf(Syllables.id("ke"), Syllables.id("yi"))))
        val added = PinyinSession(
            data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA, neighbours = true), pageSize = 20, prediction = false, user = learner.model,
            learnTo = learner.learnTo(kind), addedWords = { setOf("可以") },
        )
        // 苦, which only the slip reads, is neither raised nor moved: where the decoder put it, the words ranked around it
        val shown = added.tap("ky", touches)
        assertEquals("$shown", listOf("快", "苦", "可以"), shown.take(3))
        assertEquals(listOf("快", "可以"), added.tap("ky", at("ky")).take(2))
        // typed: the same
        val typing = Learner(data)
        val s = session(typing)
        val whole = "keyi"
        s.apply(Select(whole.indices.map { s.apply(Key(whole[it])) }.last().candidates.indexOf("可以")))
        s.apply(Reset)
        assertEquals(listOf("快", "苦", "可以"), s.tap("ky", touches).take(3))
    }
}
