/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.remote

import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RemoteRefinerTest {

    private val remote = FakeRemoteModel()
    private val clock = FakeClock()
    private val readings = listOf("在吗", "再吗", "栽吗")
    private val scores = listOf(-1f, -2f, -3f)

    // no time passes in a wait: the fake's futures are not done until the test says
    private fun refiner(local: SentenceRefiner? = null) = RemoteRefiner(local, remote, timeout = 300, wait = 0, clock = clock)

    @Test
    fun theServersBestComesFirstLessAPenaltyForEachPlaceDown() {
        val refiner = refiner()
        assertNull(refiner.refine("你", readings, scores, 1))
        assertEquals(listOf("你" to readings), remote.scored)
        // 栽吗 is two places down: 2 nats better than 在吗 is not enough, 2.5 is; not enough, what
        // is shown stays (with no local pick, it may be the smaller model's, not the decoder's first)
        remote.scores[0].complete(floatArrayOf(-5f, -9f, -3f))
        assertEquals(SentenceRefiner.NONE, refiner.refine("你", readings, scores, 1))
        val again = refiner()
        again.refine("你", readings, scores, 1)
        remote.scores[1].complete(floatArrayOf(-5f, -9f, -2.5f))
        assertEquals(2, again.refine("你", readings, scores, 1))
    }

    @Test
    fun theLocalRefinerGoesFirstAndItsPickIsWhereThePenaltyCountsFrom() {
        var slices = 0
        val local = SentenceRefiner { _, _, _, _ -> if (++slices < 3) null else 1 }
        val refiner = refiner(local)
        // asked once it has picked: a key typed before then sends nothing
        assertNull(refiner.refine("", readings, scores, 1))
        assertNull(refiner.refine("", readings, scores, 1))
        assertTrue(remote.scored.isEmpty())
        assertNull(refiner.refine("", readings, scores, 1))
        assertEquals(3, slices)
        assertEquals(1, remote.scored.size)
        remote.scores[0].complete(floatArrayOf(-5f, -5.5f, -9f))
        assertEquals(1, refiner.refine("", readings, scores, 1))
        assertEquals(3, slices)
        assertEquals(1, remote.scored.size)
    }

    @Test
    fun theLocalRefinerHasNoMoreSlicesThanAlone() {
        var slices = 0
        val local = object : SentenceRefiner {
            override val slices = 2
            override fun refine(context: String, readings: List<String>, scores: List<Float>, budget: Int): Int? {
                slices++
                return null
            }
        }
        val refiner = refiner(local)
        repeat(3) { assertNull(refiner.refine("", readings, scores, 1)) }
        assertEquals(2, slices)
        // past them, the server's pick counts from the decoder's first
        remote.scores[0].complete(floatArrayOf(-9f, -5f, -9f))
        assertEquals(1, refiner.refine("", readings, scores, 1))
        assertEquals(2, slices)
    }

    @Test
    fun noAnswerInTimeOrAFailureLeavesTheLocalPick() {
        val local = SentenceRefiner { _, _, _, _ -> 2 }
        val refiner = refiner(local)
        assertNull(refiner.refine("", readings, scores, 1))
        clock.now = 299
        assertNull(refiner.refine("", readings, scores, 1))
        clock.now = 300
        assertEquals(2, refiner.refine("", readings, scores, 1))
        assertTrue(remote.scores[0].isCancelled)

        val failing = refiner(local)
        assertNull(failing.refine("x", readings, scores, 1))
        remote.scores[1].completeExceptionally(IOException("HTTP 500"))
        assertEquals(2, failing.refine("x", readings, scores, 1))
        // nothing local: nothing to say
        val alone = refiner()
        alone.refine("y", readings, scores, 1)
        remote.scores[2].completeExceptionally(IOException("refused"))
        assertEquals(SentenceRefiner.NONE, alone.refine("y", readings, scores, 1))
    }

    @Test
    fun theServerHasItsWholeTimeoutHoweverLongTheLocalRefinerTook() {
        var slices = 0
        val local = SentenceRefiner { _, _, _, _ -> if (++slices < 3) null else 0 }
        val refiner = refiner(local)
        assertNull(refiner.refine("", readings, scores, 1))
        clock.now = 250
        assertNull(refiner.refine("", readings, scores, 1))
        assertNull(refiner.refine("", readings, scores, 1))
        assertEquals(1, remote.scored.size)
        clock.now = 549
        assertNull(refiner.refine("", readings, scores, 1))
        remote.scores[0].complete(floatArrayOf(-9f, -1f, -9f))
        assertEquals(1, refiner.refine("", readings, scores, 1))
        // and no more: a new input (the local picks at once now) is given up on 300 after it was asked
        assertNull(refiner.refine("x", readings, scores, 1))
        clock.now = 848
        assertNull(refiner.refine("x", readings, scores, 1))
        clock.now = 849
        assertEquals(0, refiner.refine("x", readings, scores, 1))
    }

    @Test
    fun aQuestionThatGotNoAnswerIsAskedAgainWhenItsInputComesBack() {
        val refiner = refiner()
        assertNull(refiner.refine("", readings, scores, 1))
        clock.now = 300
        assertEquals(SentenceRefiner.NONE, refiner.refine("", readings, scores, 1))
        assertNull(refiner.refine("", readings, scores, 1))
        assertEquals(2, remote.scored.size)
        // one answered is kept: typed again, the same input is not asked again
        remote.scores[1].complete(floatArrayOf(-9f, -1f, -9f))
        assertEquals(1, refiner.refine("", readings, scores, 1))
        assertEquals(1, refiner.refine("", readings, scores, 1))
        assertEquals(2, remote.scored.size)
    }

    @Test
    fun aNewInputIsANewQuestionAndTheOldOneIsCancelled() {
        val refiner = refiner()
        refiner.refine("", readings, scores, 1)
        refiner.refine("", readings.take(2), scores, 1)
        assertEquals(2, remote.scored.size)
        assertTrue(remote.scores[0].isCancelled)
        // only the first few go
        refiner.refine("", List(8) { "$it" }, List(8) { 0f }, 1)
        assertEquals(RemoteRefiner.LIMIT, remote.scored[2].second.size)
    }

    @Test
    fun oneReadingIsTheLocalsAloneAndOfflineIsTheLocal() {
        val local = SentenceRefiner { _, _, _, _ -> 0 }
        val refiner = refiner(local)
        assertEquals(0, refiner.refine("", listOf("在"), listOf(0f), 1))
        assertEquals(SentenceRefiner.NONE, refiner().refine("", listOf("在"), listOf(0f), 1))
        // the local's "more slices to come" stays that
        assertNull(refiner { _, _, _, _ -> null }.refine("", listOf("在"), listOf(0f), 1))
        assertTrue(remote.scored.isEmpty())
        assertSame(local, refiner.offline())
        assertNull(refiner().offline())
        // waits out its timeout a slice at a time, after what the local takes
        assertEquals(SentenceRefiner.SLICES + 301, refiner.slices)
    }
}
