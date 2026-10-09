/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The punctuation page saves on every edit; a save waits while fcitx is not ready. Those saves
 * used to be separate coroutines, so an older table could be written after a newer one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LatestWriterTest {

    @Test
    fun whatAWaitingWriteHeldUpLandsNewestLastAndWhatWasReplacedIsSkipped() = runTest {
        val written = mutableListOf<Int>()
        val ready = CompletableDeferred<Unit>()
        val writer = LatestWriter(backgroundScope) { throw it }

        writer.submit { ready.await(); written += 1 }
        runCurrent()
        writer.submit { written += 2 }
        writer.submit { written += 3 }
        ready.complete(Unit)
        runCurrent()

        assertEquals(listOf(1, 3), written)
    }

    @Test
    fun aFailedWriteIsReportedAndTheNextOneStillRuns() = runTest {
        val failures = mutableListOf<String?>()
        val written = mutableListOf<Int>()
        val writer = LatestWriter(backgroundScope) { failures += it.message }

        writer.submit { error("disconnected") }
        runCurrent()
        writer.submit { written += 2 }
        runCurrent()

        assertEquals(listOf("disconnected"), failures)
        assertEquals(listOf(2), written)
    }

    @Test
    fun closingStillWritesWhatIsWaitingAndDropsWhatComesAfter() = runTest {
        val written = mutableListOf<Int>()
        val ready = CompletableDeferred<Unit>()
        val writer = LatestWriter(backgroundScope) { throw it }

        writer.submit { ready.await(); written += 1 }
        runCurrent()
        writer.submit { written += 2 }
        writer.close()
        writer.submit { written += 3 }
        ready.complete(Unit)
        runCurrent()

        assertEquals(listOf(1, 2), written)
    }
}
