/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.content.ClipData
import androidx.paging.PagingSource
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * uat/05-toolbar.md's clipboard: what the history keeps of a copy, as the listener the app's start
 * registers takes it (ClipboardManagerReopenTest's way), the panel's list being those entries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ClipboardUatTest {

    @Before
    fun fresh() = ClipboardManager.resetDuplicateCheck()

    private fun copy(text: String) = runBlocking {
        ClipboardManager.coroutineContext.job.children.forEach { it.join() }
        appContext.clipboardManager.setPrimaryClip(ClipData.newPlainText(null, text))
        ClipboardManager.coroutineContext.job.children.forEach { it.join() }
    }

    private fun history() = runBlocking {
        ClipboardManager.coroutineContext.job.children.forEach { it.join() }
        (0 until 50).mapNotNull { ClipboardManager.get(it)?.text }
    }

    @Test
    @Uat("TB-13")
    fun aCopiedLinkLosesItsTrackingParameters() {
        copy("https://example.com/a?id=1&utm_source=x")
        assertEquals("https://example.com/a?id=1", ClipboardManager.lastEntry?.text)
        // the system's paste: the clip as copied
        assertEquals("https://example.com/a?id=1&utm_source=x", appContext.clipboardManager.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    @Uat("TB-14")
    fun historyOffKeepsNothing() {
        val listening = AppPrefs.getInstance().clipboard.clipboardListening
        val was = listening.getValue()
        try {
            listening.setValue(false)
            copy("公园")
            assertTrue(history().toString(), "公园" !in history())
        } finally {
            listening.setValue(was)
        }
    }

    private fun panel() = runBlocking {
        ClipboardManager.coroutineContext.job.children.forEach { it.join() }
        val page = ClipboardManager.allEntries().load(PagingSource.LoadParams.Refresh(null, 50, false))
        (page as PagingSource.LoadResult.Page).data
    }

    @Test
    @Uat("TB-50")
    fun anEntryPastedFromThePanelMovesFirstOfTheUnpinned() = runBlocking {
        // a clip a moment after the last: the same time is taken for the same clip again
        for (text in listOf("甲", "乙", "丙")) {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            copy(text)
        }
        val entries = panel()
        assertEquals("丙", entries.first().text)
        ClipboardManager.used(entries.first { it.text == "乙" }.id, now = entries.maxOf { it.timestamp } + 1)
        assertEquals("乙", panel().first { !it.pinned }.text)
    }
}
