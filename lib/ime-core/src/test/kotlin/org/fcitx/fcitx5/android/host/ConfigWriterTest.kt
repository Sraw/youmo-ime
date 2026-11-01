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
    private val record: (String, String) -> Boolean = { p, t -> written += p to t; true }

    private fun runTasks() {
        while (tasks.isNotEmpty()) tasks.removeAt(0).run()
    }

    @Test
    fun `changes before the write are merged into one write of the latest text`() {
        val writer = ConfigWriter(true, tasks::add, record)
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
        val writer = ConfigWriter(true, tasks::add, record)
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
            true
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

    @Test
    fun `a file held is not written, what was pending for it dropped, the others written`() {
        // A4: a file there that could not be read is held, so the defaults read in its place never replace it
        val writer = ConfigWriter(true, tasks::add, record)
        writer.write("conf/androidengine.conf") { "PageSize=9" }
        writer.hold("conf/androidengine.conf")
        writer.write("conf/androidengine.conf") { "PageSize=5" }
        writer.write("profile") { "p" }
        runTasks()
        writer.write("conf/androidengine.conf") { "PageSize=7" }
        writer.flush()
        assertEquals(listOf("profile" to "p"), written)
    }

    @Test
    fun `a file held then read again is written again`() {
        // a reload that read it after all: what the host holds of it is the file's
        val writer = ConfigWriter(true, tasks::add, record)
        writer.hold("conf/androidengine.conf")
        writer.release("conf/androidengine.conf")
        writer.write("conf/androidengine.conf") { "PageSize=7" }
        writer.flush()
        assertEquals(listOf("conf/androidengine.conf" to "PageSize=7"), written)
    }

    // review F4900: the held check while a drain writes had no test that failed without it
    @Test
    fun `a file held while a drain writes the batch it is in is not written`() {
        // a reload failing on another thread after the batch was taken, before the file's turn in it
        lateinit var writer: ConfigWriter
        writer = ConfigWriter(true, tasks::add) { p, t ->
            written += p to t
            if (p == "profile") writer.hold("conf/androidengine.conf")
            true
        }
        writer.write("profile") { "p" }
        writer.write("conf/androidengine.conf") { "PageSize=5" }
        writer.flush()
        assertEquals(listOf("profile" to "p"), written)
    }

    @Test
    fun `what waits for a write runs once it is on disk, a later write merged in included`() {
        val landed = mutableListOf<String>()
        val writer = ConfigWriter(true, tasks::add, record)
        writer.write("conf/pinyin.conf", { landed += "pin" }) { "ShuangpinProfile=Ziranma" }
        // the user's change before it ran: one write, of the latest text, and still what was waiting
        writer.write("conf/pinyin.conf") { "ShuangpinProfile=MS" }
        assertTrue(landed.isEmpty())
        runTasks()
        assertEquals(listOf("pin"), landed)
        // run once
        writer.write("conf/pinyin.conf") { "x" }
        runTasks()
        assertEquals(listOf("pin"), landed)
    }

    @Test
    fun `what waits for a write runs not while it fails, once a later write lands, and never while held`() {
        val landed = mutableListOf<String>()
        var full = true
        val failing = ConfigWriter(true, tasks::add) { p, t -> !full && record(p, t) }
        failing.write("conf/pinyin.conf", { landed += "failed" }) { "ShuangpinProfile=Ziranma" }
        runTasks()
        assertTrue(landed.toString(), landed.isEmpty())
        // put back, not lost: the next write of the path that lands runs it, once
        full = false
        failing.write("conf/pinyin.conf") { "ShuangpinProfile=Ziranma" }
        runTasks()
        failing.write("conf/pinyin.conf") { "ShuangpinProfile=Ziranma" }
        runTasks()
        assertEquals(listOf("failed"), landed)
        written.clear()
        landed.clear()
        val held = ConfigWriter(true, tasks::add, record)
        held.hold("conf/pinyin.conf")
        held.write("conf/pinyin.conf", { landed += "held" }) { "ShuangpinProfile=Ziranma" }
        runTasks()
        assertTrue(landed.toString(), landed.isEmpty())
        assertTrue(written.isEmpty())
    }

    @Test
    fun `what waits for a write asked for while an older text is written runs only once its own lands`() {
        val landed = mutableListOf<String>()
        lateinit var writer: ConfigWriter
        writer = ConfigWriter(true, tasks::add) { p, t ->
            written += p to t
            // a page saved while the old text is being written: the pin asked for now is not in it
            if (t == "old") writer.write(p, { landed += "pin" }) { "ShuangpinProfile=Ziranma" }
            true
        }
        writer.write("conf/pinyin.conf") { "old" }
        tasks.removeAt(0).run()
        assertTrue(landed.isEmpty())
        runTasks()
        assertEquals(listOf("pin"), landed)
        assertEquals("ShuangpinProfile=Ziranma", written.last().second)
    }

    @Test
    fun `what waits for a write held before it ran does not run at a write after the release`() {
        val landed = mutableListOf<String>()
        val writer = ConfigWriter(true, tasks::add, record)
        writer.write("conf/pinyin.conf", { landed += "pin" }) { "a" }
        writer.hold("conf/pinyin.conf")
        writer.release("conf/pinyin.conf")
        writer.write("conf/pinyin.conf") { "b" }
        runTasks()
        assertTrue(landed.isEmpty())
    }
}
