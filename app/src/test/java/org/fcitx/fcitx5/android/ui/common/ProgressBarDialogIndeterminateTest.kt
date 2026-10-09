/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The loading dialog is taken down however the work ends, so a non-cancelable one never stays up. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressBarDialogIndeterminateTest {

    private val shown = mutableListOf<String>()
    private val dismissed = mutableListOf<String>()

    private suspend fun load(action: suspend () -> Unit) =
        withLoading<String>(200L, { "dialog".also { shown += it } }, { dismissed += it }, action)

    @Test
    fun quickWorkShowsNothing() = runTest {
        load { delay(100) }
        advanceUntilIdle()
        assertTrue(shown.isEmpty())
        assertTrue(dismissed.isEmpty())
    }

    @Test
    fun slowWorkDismissesWhatItShowed() = runTest {
        load { delay(500) }
        assertEquals(listOf("dialog"), shown)
        assertEquals(listOf("dialog"), dismissed)
    }

    @Test
    fun failedWorkStillDismisses() = runTest {
        val failure = runCatching {
            load {
                delay(500)
                throw IOException("unreadable")
            }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(listOf("dialog"), dismissed)
    }

    @Test
    fun cancelledWorkStillDismisses() = runTest {
        val job = launch { load { awaitCancellation() } }
        advanceTimeBy(300)
        assertEquals(listOf("dialog"), shown)
        job.cancelAndJoin()
        assertEquals(listOf("dialog"), dismissed)
    }
}
