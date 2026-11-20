/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A heap dump holds typed text: one an unfinished export left is deleted, the one the picker waits on is not. */
class DeveloperFragmentTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun cache(vararg names: String): File = folder.newFolder("cache").apply {
        names.forEach { resolve(it).createNewFile() }
    }

    @Test
    fun everyDumpButTheOneAnExportIsWaitingOnIsStale() {
        val dir = cache("a.hprof", "b.hprof", "log.txt")
        val stale = staleDumps(dir.listFiles()!!.asList(), dir.resolve("b.hprof"))
        assertEquals(setOf("a.hprof"), stale.map { it.name }.toSet())
    }

    @Test
    fun withNoExportWaitingEveryDumpIsStale() {
        val dir = cache("a.hprof", "b.hprof", "log.txt")
        val stale = staleDumps(dir.listFiles()!!.asList(), null)
        assertEquals(setOf("a.hprof", "b.hprof"), stale.map { it.name }.toSet())
    }
}
