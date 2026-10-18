/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

@OptIn(ExperimentalCoroutinesApi::class)
class BackupFragmentTest {

    // a reopen that still throws, its count failing on the full disk say (SQLiteFullException, a stub off the device)
    @Test
    fun fcitxStartsAgainThoughTheClipboardCannotReopen() = runTest {
        val done = mutableListOf<String>()
        restartAfterFailedImport({
            done += "clipboard"
            throw IllegalStateException("database or disk is full")
        }) { done += "fcitx" }
        assertEquals(listOf("clipboard", "fcitx"), done)
    }

    @Test
    fun anErrorReopeningTheClipboardGoesOnAfterFcitxStarts() = runTest {
        val done = mutableListOf<String>()
        val thrown = runCatching { restartAfterFailedImport({ throw AssertionError("a bug") }) { done += "fcitx" } }.exceptionOrNull()
        // rethrown, not made a log line
        assertTrue(thrown.toString(), thrown is Error)
        // a page gone, no one may await the Error: the keyboard is not left without fcitx for it
        assertEquals(listOf("fcitx"), done)
    }

    @Test
    fun aCancellationWhileTheClipboardReopensGoesOnUnloggedAfterFcitxStarts() = runTest {
        val done = mutableListOf<String>()
        val logged = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logged += message
            }
        }
        Timber.plant(tree)
        try {
            val job = launch {
                restartAfterFailedImport({ awaitCancellation() }) { done += "fcitx" }
                done += "after"
            }
            runCurrent()
            job.cancel()
            // its catch over before done is read
            job.join()
        } finally {
            Timber.uproot(tree)
        }
        // rethrown, so nothing after it runs; no failure logged
        assertEquals(listOf("fcitx"), done)
        assertEquals(emptyList<String>(), logged)
    }
}
