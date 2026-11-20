/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.input.voice.VoiceHotwords.Words
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CancelledElsewhereTest {

    // VoiceEngine.userHotwords awaits a read on a scope of its own: that scope ending cancels the read, not the listener
    @Test
    fun aReadCancelledFromElsewhereIsTheFallback() = runBlocking {
        val reading = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val read = reading.async { awaitCancellation() }
        val got = async { cancelledElsewhereAs("none") { read.await() } }
        reading.cancel()
        assertEquals("none", got.await())
    }

    @Test
    fun theCallerCancelledStaysCancelled() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var after: String? = null
        val got = async {
            after = cancelledElsewhereAs("none") {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        got.cancel()
        val thrown = runCatching { got.await() }.exceptionOrNull()
        assertTrue("$thrown", thrown is CancellationException)
        assertEquals(null, after)
    }

    @Test
    fun aHotwordsReadCancelledElsewhereIsTheLastRead() = runBlocking {
        val remembered = VoiceHotwords.Remembered()
        val last = Words("你好", "")
        remembered.read(remembered.shared<Long>({ false }) { it }, last)
        val reading = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val read = reading.async<Words> { awaitCancellation() }
        val got = async { inTimeOrLast(read, remembered, 10_000) }
        reading.cancel()
        assertEquals(last, got.await())
    }

    @Test
    fun aHotwordsReadThatFailedIsTheLastRead() = runBlocking {
        val remembered = VoiceHotwords.Remembered()
        val last = Words("你好", "坏词")
        remembered.read(remembered.shared<Long>({ false }) { it }, last)
        val read = CompletableDeferred<Words>().apply { completeExceptionally(IllegalStateException("engine")) }
        assertEquals(last, inTimeOrLast(read, remembered, 10_000))
    }
}
