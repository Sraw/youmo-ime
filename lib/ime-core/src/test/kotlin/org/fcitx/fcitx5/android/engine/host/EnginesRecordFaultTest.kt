/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.session.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** What the engines do when the disk refuses the learning record a write (review R11 F2743). */
class EnginesRecordFaultTest : EnginesFixture() {

    @get:Rule
    val folder = TemporaryFolder()

    // review R11 F2743: an append the disk refuses is reported once; the act is done, and kept in memory for the run
    @Test
    fun anAppendRefusedIsReportedOnceAndTheActKeptInMemory() {
        val dir = folder.newFolder("engine")
        val errors = ArrayList<IOException>()
        Engines(::load, dir, onError = { errors += it }).use { engines ->
            // 拟 used once, written: a word learned there, to delete
            engines.type(Engines.PINYIN, "ni")
            engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 1)
            assertEquals(listOf("你", "拟"), engines.type(Engines.PINYIN, "ni").candidates)
            assertTrue(Offer.FORGET in engines.offers(Engines.PINYIN, 1))
            refuseNextAppend(engines)
            assertTrue(engines.learning(Engines.PINYIN, LearningOp.Delete(1, "拟")) is LearningResult.Done)
            assertEquals(1, errors.size)
            // forgotten for the run all the same
            assertFalse(Offer.FORGET in engines.offers(Engines.PINYIN, 1))
            refuseNextAppend(engines)
            assertTrue(engines.learning(Engines.PINYIN, LearningOp.Pin(1, "拟")) is LearningResult.Done)
            assertEquals(2, errors.size)
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            assertEquals(listOf("拟", "你"), engines.type(Engines.PINYIN, "ni").candidates)
        }
    }

    // the learning record (opened if not yet) with its next write refused: the store reports it, then opens the file again
    private fun refuseNextAppend(engines: Engines) {
        // opened as its first use opens it
        checkNotNull(engines.learnLog().store).refuseNextWrite()
    }
}
