/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.NextPage
import org.fcitx.fcitx5.android.engine.session.Action.Reset
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PinyinSession] with a refiner: what it weighs again while the user pauses, and when. */
class PinyinSessionRefineTest {

    private val data = sessionTestData()

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    @Test
    fun whileTheUserPausesTheRefinerPutsItsPickFirst() {
        val budgets = ArrayList<Int>()
        val refiner = SentenceRefiner { _, readings, _, budget ->
            budgets += budget
            if (budgets.size < 3) null else readings.lastIndex
        }
        val session = PinyinSession(data, PinyinSegmenter(), pageSize = 20, refiner = refiner) { _, _, _ -> 0 }
        val typed = session.type("nizai")
        assertTrue(typed.refines)
        // slices that pick nothing leave it as it was
        repeat(2) { assertEquals(typed, session.apply(Action.Refine)) }
        val refined = session.apply(Action.Refine)
        assertFalse(refined.refines)
        assertNotEquals(typed.candidates[0], refined.candidates[0])
        assertEquals(typed.candidates.toSet(), refined.candidates.toSet())
        assertEquals(List(3) { PinyinSession.REFINE_BUDGET }, budgets)
        // done: nothing more asked of it
        assertEquals(refined, session.apply(Action.Refine))
        assertEquals(3, budgets.size)
        // any other action drops what was left: the refiner weighs the first page as last read
        assertTrue(session.type("ma").refines)
        assertFalse(session.apply(NextPage).refines)
        session.apply(Action.Refine)
        assertEquals(3, budgets.size)
    }

    @Test
    fun aSyllableStillBeingTypedIsNotRefined() {
        val session = PinyinSession(data, PinyinSegmenter(), refiner = { _, readings, _, _ -> readings.lastIndex }) { _, _, _ -> 0 }
        assertTrue(session.type("nizai").refines)
        // initials, then the start of a syllable: nothing for the refiner to swap in till it is whole
        assertFalse(session.apply(Key('z')).refines)
        assertFalse(session.apply(Key('h')).refines)
        assertFalse(session.apply(Key('o')).refines)
        session.apply(Key('n'))
        assertTrue(session.apply(Key('g')).refines)
    }

    @Test
    fun aTrailingNOrMIsTheNextSyllableStartingNotAWholeOne() {
        val session = PinyinSession(data, PinyinSegmenter(), refiner = { _, readings, _, _ -> readings.lastIndex }) { _, _, _ -> 0 }
        // 嗯 and 呣 are syllables, but not what a trailing n or m is being typed for
        assertFalse(session.type("nizain").refines)
        session.apply(Reset)
        assertFalse(session.type("woxiangm").refines)
        assertTrue(session.type("ai").refines)
    }

    @Test
    fun aRefinerThatNeverPicksIsGivenUpOnAfterItsSlices() {
        var asked = 0
        val session = PinyinSession(data, PinyinSegmenter(), refiner = { _, _, _, _ -> asked++; null }) { _, _, _ -> 0 }
        var shown = session.type("nizai")
        var slices = 0
        while (shown.refines) {
            shown = session.apply(Action.Refine)
            slices++
            assertTrue(slices <= SentenceRefiner.SLICES + 1)
        }
        assertEquals(SentenceRefiner.SLICES, asked)
        // the next input gets its own
        assertTrue(session.type("ma").refines)
    }

    /**
     * Keeps its work as [org.fcitx.fcitx5.android.engine.rerank.Reranker]'s scorer does: a unit for
     * each start of a reading, [budget] of them a call, kept for later calls; all done, the last
     * reading is its pick. [slices] few, for an input's work to pass them.
     */
    private class KeepingRefiner(override val slices: Int) : SentenceRefiner {
        private val done = HashSet<String>()

        private fun units(readings: List<String>) = readings.flatMap { r -> (1..r.length).map { r.take(it) } }.toSet()

        override fun refine(context: String, readings: List<String>, scores: List<Float>, budget: Int): Int? {
            done += (units(readings) - done).take(budget)
            return if (done.containsAll(units(readings))) readings.lastIndex else null
        }
    }

    private fun PinyinSession.paused(): Snapshot {
        var shown = apply(Action.Refine)
        while (shown.refines) shown = apply(Action.Refine)
        return shown
    }

    @Test
    fun slicesBetweenKeysAreReusedAtThePauseSoTheTapPaceMayChangeTheResult() {
        // DECISIONS.md 2026-10-25 (r2-7560): the pause goes on from work slices between keys did, its
        // slices capped; typed with no slice between keys, the same input may give up where slow typing picks
        fun typed(slow: Boolean): Snapshot {
            val session = PinyinSession(data, PinyinSegmenter(), refiner = KeepingRefiner(slices = 6)) { _, _, _ -> 0 }
            for (c in "nizaima") {
                val shown = session.apply(Key(c))
                if (slow && shown.refines) session.paused()
            }
            return session.paused()
        }
        assertNotEquals(typed(slow = false).candidates[0], typed(slow = true).candidates[0])
    }

    @Test
    fun whileLearningIsOffTheRefinerIsToldNothingOfTheTextBefore() {
        val contexts = ArrayList<String>()
        val refiner = SentenceRefiner { context, _, _, _ -> contexts += context; SentenceRefiner.NONE }
        val session = PinyinSession(data, PinyinSegmenter(), refiner = refiner) { _, _, _ -> 0 }
        // what the refiner is told is before nizai, typed and paused on
        fun told(): String {
            assertTrue(session.type("nizai").refines)
            session.apply(Action.Refine)
            return contexts.last()
        }
        session.type("nizai")
        val committed = session.apply(Select(0)).commit
        assertEquals(committed, told())
        session.apply(Action.Context("他说我"))
        assertEquals("他说我", told())
        session.apply(Select(0))
        // a password: neither what was committed before it nor the app's text
        session.learning = false
        assertEquals("", told())
        session.apply(Action.Context("他说我"))
        assertEquals("", told())
    }

    @Test
    fun aRefinerWithNothingToSayLeavesTheRerankersPick() {
        val refiner = SentenceRefiner { _, _, _, _ -> SentenceRefiner.NONE }
        val session = PinyinSession(data, PinyinSegmenter(), refiner = refiner) { _, readings, _ -> readings.lastIndex }
        val typed = session.type("nizai")
        val refined = session.apply(Action.Refine)
        assertFalse(refined.refines)
        assertEquals(typed.candidates, refined.candidates)
    }
}
