/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class ConfigWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val tasks = ArrayList<Runnable>()
    private val written = ArrayList<Pair<String, String>>()

    private fun runTasks() {
        while (tasks.isNotEmpty()) tasks.removeAt(0).run()
    }

    @Test
    fun `changes before the write are merged into one write of the latest text`() {
        val writer = ConfigWriter(true, tasks::add) { p, t -> written += p to t }
        var state = "a"
        writer.write("conf/chttrans.conf") { state }
        state = "b"
        writer.write("conf/chttrans.conf") { state }
        writer.write("profile") { "p" }
        assertEquals(1, tasks.size)
        assertTrue(written.isEmpty())
        runTasks()
        assertEquals(listOf("conf/chttrans.conf" to "b", "profile" to "p"), written)
    }

    @Test
    fun `a state change is written without a settings page closing or a stop`() {
        // native fcitx saved state like 繁體 only at MainActivity.onStop: a process killed from
        // the keyboard lost it. Here the change alone is enough, once the background thread runs
        val writer = ConfigWriter(true, tasks::add, ConfigWriter.files(tmp.root) { _, e -> throw e })
        writer.write("conf/chttrans.conf") { "[EnabledIM]\n0=engine-pinyin\n" }
        runTasks()
        assertEquals("[EnabledIM]\n0=engine-pinyin\n", File(tmp.root, "conf/chttrans.conf").readText())
        assertFalse(File(tmp.root, "conf/chttrans.conf.new").exists())
    }

    @Test
    fun `flush writes what is pending at once, not on the background thread`() {
        val writer = ConfigWriter(true, tasks::add) { p, t -> written += p to t }
        writer.write("profile") { "p" }
        writer.flush()
        assertEquals(listOf("profile" to "p"), written)
        runTasks()
        assertEquals(1, written.size)
    }

    @Test
    fun `a flush waits for a write the background thread already took`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = mutableListOf<String>()
        val background = Executors.newSingleThreadExecutor()
        val writer = ConfigWriter(true, background) { p, _ ->
            started.countDown()
            release.await()
            synchronized(done) { done += p }
        }
        try {
            writer.write("conf/androidengine.conf") { "PageSize=9" }
            started.await()
            val flushed = Thread { writer.flush() }.apply { start() }
            flushed.join(200)
            assertTrue("save() returned before the file was written", flushed.isAlive)
            release.countDown()
            flushed.join()
            assertEquals(listOf("conf/androidengine.conf"), synchronized(done) { done.toList() })
        } finally {
            // a failed check must not leave the worker parked on release
            release.countDown()
            background.shutdownNow()
        }
    }

    @Test
    fun `before unlock nothing is written, flush included`() {
        // design §2.2: direct boot reads only; the process is stopped at unlock (FcitxApplication.kt:53-62)
        val root = tmp.newFolder("config")
        val writer = ConfigWriter(false, tasks::add, ConfigWriter.files(root) { _, e -> throw e })
        writer.write("conf/androidengine.conf") { "PageSize=5\n" }
        writer.write("profile") { "x" }
        writer.flush()
        runTasks()
        assertTrue(tasks.isEmpty())
        assertEquals(0, root.walk().count { it.isFile })
    }
}
