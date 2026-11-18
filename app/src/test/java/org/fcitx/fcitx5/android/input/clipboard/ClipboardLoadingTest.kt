/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.annotation.SuppressLint
import android.widget.TextView
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.DefaultTaskExecutor
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The empty history's panel while Room's first query of it is out: the hint, not the bar's clear button over no rows.
 * The paging's first load state (refresh loading, append not at its end) was taken for a list; a full run's load held
 * the query past LowLandscapeScreenshotTest's shot of the panel (R27), which alone it came back before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class ClipboardLoadingTest {

    private val h = KeyboardHarness()

    @get:Rule
    val prefs = PrefsRule()

    // Room's queries, the paging's among them, run on the architecture components' disk executor: held here
    private val held = ConcurrentLinkedQueue<Runnable>()

    @SuppressLint("RestrictedApi")
    private val holding = object : DefaultTaskExecutor() {
        override fun executeOnDiskIO(runnable: Runnable) {
            held += runnable
        }
    }

    @SuppressLint("RestrictedApi")
    @After
    fun close() {
        // let go before the harness's close, whose emptying of the database is a query too
        ArchTaskExecutor.getInstance().setDelegate(null)
        generateSequence { held.poll() }.forEach(ArchTaskExecutor.getIOThreadExecutor()::execute)
        h.close()
    }

    @SuppressLint("RestrictedApi")
    @Test
    fun theEmptyHistorysHintStaysWhileItsListLoads() {
        h.start()
        // the start's open of the database in
        runBlocking { ClipboardManager.coroutineContext.job.children.forEach { it.join() } }
        ArchTaskExecutor.getInstance().setDelegate(holding)
        h.clipboard()
        assertFalse("no query let through", held.isEmpty())
        val shown = KeyboardHarness.all(h.inputView).filter(h::shown)
        val hint = h.service.getString(R.string.instruction_copy)
        assertTrue("the hint shown", shown.any { it is TextView && it.text.toString() == hint })
        val clear = h.service.getString(R.string.delete_all)
        assertFalse("the clear button shown", shown.any { it.contentDescription == clear })
    }
}
