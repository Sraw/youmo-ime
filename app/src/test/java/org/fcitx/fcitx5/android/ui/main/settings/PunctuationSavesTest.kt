/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.fcitx.fcitx5.android.core.FakeHostConnection
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

/**
 * Each instance of the punctuation page had a writer of its own on the page's connection: a page
 * made anew could read the table before the last one's saves landed, and a save waiting for fcitx
 * when the page closed landed at fcitx's next start while the newer ones failed as disconnected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PunctuationSavesTest {

    /** Fcitx as the saves reach it: ready once [ready] completes, then failing with [failure] if set. */
    private class Saver(scope: CoroutineScope, val fake: FakeHostConnection = FakeHostConnection(scope)) :
        HostConnection by fake {

        var ready = CompletableDeferred<Unit>()
        var failure: Throwable? = null

        /** The next call is one fcitx's stop drops: it never returns, cancelled or not. */
        var hangs = false
        private val dropped = mutableListOf<Continuation<Unit>>()

        override suspend fun <T> runOnReady(block: suspend HostApi.() -> T): T {
            ready.await()
            if (hangs) {
                hangs = false
                suspendCoroutine<Unit> { dropped += it }
            }
            failure?.let { throw it }
            return fake.runOnReady(block)
        }

        val writes: Int
            get() = fake.api.calls.count { it.startsWith("setPunctuationMap(") }

        suspend fun saved(lang: String) = fake.api.punctuationMap(lang)
    }

    /** [fcitx]: fcitx's own scope, whose coroutines its stop cancels. */
    private class Fixture(scope: CoroutineScope, fcitx: CoroutineScope = scope) {
        val saver = Saver(fcitx)
        // the page's own, ready at once
        val page = FakeHostConnection(scope)
        var held = false
        val failures = mutableListOf<String?>()
        val saves = PunctuationSaves(scope, connect = { held = true; saver }, disconnect = { held = false }) {
            failures += it.message
        }
    }

    private val comma = listOf(PunctuationMapEntry(",", "，", ""))
    private val both = comma + PunctuationMapEntry(".", "。", "")

    @Test
    fun aPageMadeAnewReadsTheTableOnceTheSavesBeforeItHaveLanded() = runTest {
        val f = Fixture(backgroundScope)
        f.saves.save("zh_CN", both)
        val loaded = async { f.saves.load(f.page, "zh_CN") }
        val other = async { f.saves.load(f.page, "zh_TW") }
        runCurrent()
        assertFalse(loaded.isCompleted)
        assertTrue("another language's table is not held up", other.isCompleted)

        f.saver.ready.complete(Unit)
        runCurrent()
        assertTrue(loaded.isCompleted)
        assertEquals(both, f.saver.saved("zh_CN"))
    }

    @Test
    fun fcitxIsHeldUntilTheNewestSaveLands() = runTest {
        val f = Fixture(backgroundScope)
        f.saves.save("zh_CN", comma)
        runCurrent()
        f.saves.save("zh_CN", both)
        val first = f.saver.ready
        f.saver.ready = CompletableDeferred()

        first.complete(Unit)
        runCurrent()
        assertEquals(1, f.saver.writes)
        assertTrue("the newest still waits", f.held)

        f.saver.ready.complete(Unit)
        runCurrent()
        assertEquals(2, f.saver.writes)
        assertEquals(both, f.saver.saved("zh_CN"))
        assertFalse(f.held)
    }

    @Test
    fun aFailedSaveIsReportedAndHoldsNothingUp() = runTest {
        val f = Fixture(backgroundScope)
        f.saver.failure = IllegalStateException("not saved")
        f.saver.ready.complete(Unit)
        f.saves.save("zh_CN", both)
        runCurrent()
        assertEquals(listOf("not saved"), f.failures)
        assertFalse(f.held)

        val loaded = async { f.saves.load(f.page, "zh_CN") }
        runCurrent()
        assertTrue(loaded.isCompleted)
    }

    @Test
    fun aSaveFcitxsRestartDroppedIsMadeAgainOnceItIsReady() = runTest {
        val fcitx = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob())
        val f = Fixture(backgroundScope, fcitx)
        f.saver.ready.complete(Unit)
        f.saver.hangs = true
        f.saves.save("zh_CN", both)
        runCurrent()
        val loaded = async { f.saves.load(f.page, "zh_CN") }
        runCurrent()
        assertFalse(loaded.isCompleted)

        // the restart: the call queued for fcitx is dropped, and never returns
        fcitx.coroutineContext.cancelChildren()
        runCurrent()
        assertTrue("the page does not wait on it for good", loaded.isCompleted)
        assertEquals(both, f.saver.saved("zh_CN"))
        assertFalse(f.held)
        assertEquals(emptyList<String?>(), f.failures)
    }
}
