/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A pack's draw mark left pending (its window hidden before its frame committed) cleared once another theme is
 * drawn: the next start resets no slot for a pack no longer shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class PackDrawGuardClearTest {

    private val marks = File(appContext.cacheDir, "marks-clear")
    private val mark = File(marks, "theme-pack-drawing")

    @Before
    fun empty() {
        marks.deleteRecursively()
        marks.mkdirs()
        PackDrawGuard.dir = { marks }
        PackDrawGuard.reset()
    }

    @After
    fun putBack() {
        PackDrawGuard.dir = { appContext.noBackupFilesDir }
        PackDrawGuard.reset()
        marks.deleteRecursively()
    }

    @Test
    fun aPendingMarkIsClearedWhenAnotherThemeIsDrawn() {
        val key = PackDrawGuard.Key("mine", 1L)
        assertTrue(PackDrawGuard.drawing(key))
        assertTrue(mark.isFile)
        // the window hidden, a built-in theme picked and drawn: the pack's frame never commits
        PackDrawGuard.notDrawing()
        assertFalse(mark.isFile)
        assertNull(PackDrawGuard.crashed())
        // the pack shown again later is marked again, its first frame still unconfirmed
        assertTrue(PackDrawGuard.drawing(key))
        assertEquals("mine", mark.readText())
    }

    @Test
    fun noMarkPendingClearsNothing() {
        PackDrawGuard.notDrawing()
        assertFalse(mark.exists())
    }
}
