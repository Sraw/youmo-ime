/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Of the pages whose saves outlive them, only some caught a failed save; the others took the app down. */
@OptIn(ExperimentalCoroutinesApi::class)
class OutlivingPageTest {

    @Test
    fun aFailureIsHandedOnAndGoesNoFurther() = runTest {
        val failures = mutableListOf<String?>()
        reportingFailure({ failures += it.message }) { throw IOException("disk full") }
        reportingFailure({ failures += it.message }) { throw SecurityException("not yours") }
        assertEquals(listOf("disk full", "not yours"), failures)
    }

    @Test
    fun aBugIsNoFailureAndGoesOn() = runTest {
        val failures = mutableListOf<Throwable>()
        val thrown = runCatching { reportingFailure({ failures += it }) { error("a bug") } }.exceptionOrNull()
        assertTrue(thrown.toString(), thrown is IllegalStateException)
        assertEquals(emptyList<Throwable>(), failures)
    }

    @Test
    fun aCancellationIsNoFailure() = runTest {
        val failures = mutableListOf<Throwable>()
        val job = launch { reportingFailure({ failures += it }) { awaitCancellation() } }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(emptyList<Throwable>(), failures)
    }
}
