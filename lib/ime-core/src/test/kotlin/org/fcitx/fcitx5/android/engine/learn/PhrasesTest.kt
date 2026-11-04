/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Forget
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.PhraseForget
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.PhraseRow
import org.fcitx.fcitx5.android.engine.learn.control.LogRewrites
import org.fcitx.fcitx5.android.engine.session.PredictionOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole phrases the learning record counts (U-73): [LearnState.phrases]. */
class PhrasesTest {

    private val clock = FakeClock()
    private val log = LearnLog(null, clock).apply { open() }

    private fun whole(text: String, flags: Int = 0) = log.record(listOf(row("k", text)), flags = flags)

    private fun count(text: String) = log.state.phrases[text]?.count ?: 0

    @Test
    fun aWholeCommitOfThreeToTwelveLettersCounts() {
        repeat(3) { whole("你好呀亲爱的") }
        assertEquals(3, count("你好呀亲爱的"))
        whole("abc")
        assertEquals(1, count("abc"))
    }

    // user 2026-11-02: a number put in as words is no phrase; words with numerals in them are
    @Test
    fun chineseNumeralsAloneAreNot() {
        // 三三两两, 一五一十: words all the same, of numerals alone (the rule's cost, as the user chose it)
        for (t in listOf("一二三四五六七八九十一二", "幺三八零零", "两千五百万", "贰仟伍佰", "零一二", "壹萬億", "三三两两", "一五一十")) whole(t)
        assertTrue("${log.state.phrases}", log.state.phrases.isEmpty())
        for (t in listOf("三个人", "一起去", "贰仟伍佰元")) whole(t)
        assertEquals(setOf("三个人", "一起去", "贰仟伍佰元"), log.state.phrases.keys)
    }

    @Test
    fun shortLongAndNotLettersAreNot() {
        for (t in listOf("你好", "一二三四五六七八九十一二三", "我在3楼", "好的，谢谢", "好的 谢谢", "好的😀呀")) whole(t)
        assertTrue(log.state.phrases.isEmpty())
        assertFalse(Phrases.learnable(""))
        // the length alone: 13 letters too long, 12 not
        assertFalse(Phrases.learnable("你好呀亲爱的你好呀亲爱的呀"))
        assertTrue(Phrases.learnable("你好呀亲爱的你好呀亲爱的"))
    }

    // U-91: where no pairs are learned, no phrase either
    @Test
    fun aFieldThatLearnsNoPairsCountsNone() {
        whole("你好呀亲爱的", Commit.NO_PAIRS)
        whole("你好呀亲爱的", Commit.NO_PREDICT)
        assertEquals(0, count("你好呀亲爱的"))
    }

    @Test
    fun onlyTheWholeRowNotAPickNorARetypeNorAPrediction() {
        log.record(listOf(row("k", "你好呀", flags = Row.PICK), row("k", "亲爱的呀", flags = Row.WHOLE or Row.RETYPED)))
        log.record(listOf(PredictionOrder.row("好呀亲爱的", "你", "")))
        assertTrue(log.state.phrases.isEmpty())
    }

    @Test
    fun aPickThatWentOnWithAPhraseCountsIt() {
        log.record(listOf(PredictionOrder.row("好呀亲爱的", "你", "")), continued = "你好呀亲爱的")
        assertEquals(1, count("你好呀亲爱的"))
        assertEquals(0, count("好呀亲爱的"))
        // nor in a search field
        log.record(listOf(PredictionOrder.row("好呀亲爱的", "你", "")), flags = Commit.NO_PREDICT, continued = "你好呀亲爱的")
        assertEquals(1, count("你好呀亲爱的"))
    }

    @Test
    fun anUndoTakesItBack() {
        whole("你好呀亲爱的")
        val c = checkNotNull(whole("你好呀亲爱的"))
        assertTrue(log.undo(c.seq))
        assertEquals(1, count("你好呀亲爱的"))
        val first = log.state.phrases.keys.single()
        assertTrue(log.undo(checkNotNull(log.record(listOf(PredictionOrder.row("好呀亲爱的", "你", "")), continued = first)).seq))
        assertEquals(1, count(first))
    }

    // C52, review MUST 1: a word forgotten, of its text too, leaves the phrase; the phrase deleted leaves the words
    @Test
    fun deletedByItsTextAWordForgottenLeavesIt() {
        whole("你好呀亲爱的")
        whole("你好呀亲爱的")
        log.forget("亲爱的")
        log.forget("你好呀亲爱的")
        assertEquals(2, count("你好呀亲爱的"))
        log.absorb(listOf(LearnRecord.MineRow("你好呀亲爱的", Mine("ni hao ya qin ai de", Mine.TAKEN_IN, 1, log.state.nextSeq, 1, 0, 0))))
        log.forgetPhrase("你好呀亲爱的")
        assertNull(log.state.phrases["你好呀亲爱的"])
        assertTrue("你好呀亲爱的" in log.state.mine)
        // nothing to take off the words added by hand (LearningFacade: Changes.forgotten)
        assertTrue(log.state.takeChanges().forgotten.isEmpty())
    }

    // 30 typing days of no use, used neither whole nor picked: forgotten, as a pair is
    @Test
    fun unusedForAWindowItGoes() {
        whole("你好呀亲爱的")
        repeat(LearnState.WINDOW - 1) {
            clock.days(1)
            whole("我到楼下了")
        }
        assertEquals(1, count("你好呀亲爱的"))
        clock.days(1)
        whole("我到楼下了")
        assertEquals(0, count("你好呀亲爱的"))
        assertEquals(LearnState.WINDOW, count("我到楼下了"))
    }

    @Test
    fun aSnapshotKeepsThem() {
        repeat(2) { whole("你好呀亲爱的") }
        val rows = log.state.snapshot(0)
        assertTrue(PhraseRow("你好呀亲爱的", 2, log.state.day) in rows)
        val again = LearnState().apply { rows.forEach { apply(it) } }
        assertEquals(log.state.dump(), again.dump())
        // and a clear (U-86) drops them
        assertTrue(LogRewrites.cleared(log.state, 0, all = false).none { it is PhraseRow })
    }

    @Test
    fun onlyAPhraseForgetRemovesIt() {
        val state = LearnState()
        state.apply(PhraseRow("你好呀亲爱的", 3, 1))
        state.apply(Forget(1, 1, "你好呀亲爱的"))
        assertEquals(3, state.phrases["你好呀亲爱的"]?.count)
        state.apply(PhraseForget(1, "你好呀亲爱的"))
        assertTrue(state.phrases.isEmpty())
    }

    // an older build reads a PhraseForget as a type it does not know: kept as it is, through its fold,
    // and applied by this one when it reads the log again (after the phrase's row, as written)
    @Test
    fun anOlderBuildKeepsAPhraseForget() {
        val event = LearnRecords.encode(PhraseForget(1, "你好呀亲爱的"))
        val unknown = LearnRecord.Unknown(LearnRecords.PHRASE_FORGET, event.copyOfRange(5, event.size - 4))
        val older = LearnState()
        older.apply(LearnRecord.Unknown(LearnRecords.PHRASE_ROW, LearnRecords.encode(PhraseRow("你好呀亲爱的", 3, 1)).let { it.copyOfRange(5, it.size - 4) }))
        older.apply(unknown)
        assertTrue(older.phrases.isEmpty())
        val rows = older.snapshot(0)
        assertEquals(listOf(LearnRecords.PHRASE_ROW, LearnRecords.PHRASE_FORGET), rows.filterIsInstance<LearnRecord.Unknown>().map { it.type })
        val again = LearnState()
        rows.forEach { again.apply(checkNotNull(LearnRecords.decode(LearnRecords.encode(it)))) }
        assertTrue(again.phrases.isEmpty())
        assertTrue(again.kept.isEmpty())
    }
}
