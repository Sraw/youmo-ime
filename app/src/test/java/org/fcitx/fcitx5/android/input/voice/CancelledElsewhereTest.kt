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

/** The user's hotwords read as the models load (VoiceListener.userHotwords): which cancellation ends the talk. */
class CancelledElsewhereTest {

    // VoiceEngine.userHotwords awaits a read on a scope of its own: that scope ending (the host's
    // going away) cancels the read, not the listener, whose talk goes on without the words
    @Test
    fun aReadCancelledFromElsewhereIsTheFallback() = runBlocking {
        val reading = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val read = reading.async { awaitCancellation() }
        val got = async { cancelledElsewhereAs("none") { read.await() } }
        reading.cancel()
        assertEquals("none", got.await())
    }

    // the listener closed while they load: its cancellation goes on, not taken for no words
    @Test
    fun theCallerCancelledStaysCancelled() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var after: String? = null
        val got = async {
            // the deferred ends cancelled either way: what counts is whether the caller went on
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

    // the shared read's scope ending (VoiceEngine.userHotwords, R20 F6700): the words last read, not none
    @Test
    fun aHotwordsReadCancelledElsewhereIsTheLastRead() = runBlocking {
        val remembered = VoiceHotwords.Remembered()
        val last = Words("你好", "")
        remembered.read(remembered.start(), last)
        val reading = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val read = reading.async<Words> { awaitCancellation() }
        val got = async { inTimeOrLast(read, remembered, 10_000) }
        reading.cancel()
        assertEquals(last, got.await())
    }
}
