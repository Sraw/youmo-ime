/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class BackgroundWorkTest {

    // a count of its own: the process's is shared with the Engines tests' warm-up and compaction
    // threads, which may still be finishing as these run
    private val work = BackgroundCount()

    @Test
    fun countedFromHandedTillRunEvenWhenItThrows() {
        val queued = ArrayList<Runnable>()
        val e = work.counted(Executor { queued += it })
        e.execute {}
        e.execute { error("boom") }
        assertEquals(2, work.busy)
        queued[0].run()
        assertEquals(1, work.busy)
        assertThrows(IllegalStateException::class.java) { queued[1].run() }
        assertEquals(0, work.busy)
    }

    // each row: an executor that throws from execute; the throwable still reaches the caller, and
    // the work is counted down once, or [busy] stays wrong for good and the idle signal never comes
    @Test
    fun workAnExecutorThrowsOnIsCountedDownOnceAndRethrown() {
        data class Row(val why: String, val executor: Executor, val work: Runnable, val thrown: Class<out Throwable>)
        assertRows(
            listOf(
                Row("refused", throwing(RejectedExecutionException()), Runnable {}, RejectedExecutionException::class.java),
                // F3933: a thread that cannot be started throws an Error, not a RuntimeException
                Row("never started", throwing(OutOfMemoryError("pthread_create")), Runnable {}, OutOfMemoryError::class.java),
                // F3938: run inline, the work's own finally has counted it down already; not twice
                Row("thrown inline", Executor { it.run() }, Runnable { error("x") }, IllegalStateException::class.java),
            ),
            { it.why },
        ) { row ->
            val count = BackgroundCount()
            assertThrows(row.thrown) { count.counted(row.executor).execute(row.work) }
            assertEquals(0, count.busy)
        }
    }

    private fun throwing(t: Throwable) = Executor { throw t }
}
