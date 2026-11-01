/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.store

import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.platform.LogFile
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Appends on the thread that learns while a compaction runs on a thread of its own (review B 1.5):
 * the counts written there are put in place with every record appended meanwhile behind them, in
 * their order, and the compaction reads only the log as it was when it was due. On the disk and
 * in memory alike.
 */
class RecordStoreConcurrencyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val format = RecordFormat("TEST", 1)

    private fun added(word: String) = format.record(ADDED) { writeUTF(word) }

    private fun count(word: String, n: Int) = format.record(COUNT) {
        writeUTF(word)
        writeInt(n)
    }

    // the words of a log's records, counted
    private fun words(bytes: ByteArray): Map<String, Int> {
        val into = LinkedHashMap<String, Int>()
        format.read(bytes) { type, input -> replay(type, input, into) }
        return into
    }

    private fun replay(type: Byte, input: DataInputStream, into: MutableMap<String, Int>) {
        when (type) {
            ADDED -> {
                val word = input.readUTF()
                into[word] = (into[word] ?: 0) + 1
            }
            COUNT -> into[input.readUTF()] = input.readInt()
        }
    }

    // a thread of its own for each compaction, as Engines' default is
    private class Threads : Executor {
        val started = ArrayList<Thread>()

        override fun execute(command: Runnable) {
            started += Thread(command, "compaction").apply { start() }
        }

        // one still running after its time is a hang, failed here rather than passed over
        fun join() = started.forEach {
            it.join(TimeUnit.SECONDS.toMillis(10))
            assertFalse("${it.name} still running", it.isAlive)
        }
    }

    // the same store over either backend: each row its own file, threads and store
    private val backends: List<Pair<String, () -> LogFile>> = listOf(
        "on disk" to { JvmFiles(tmp.newFolder()).file("words.log") },
        "in memory" to { MemoryFiles().file("words.log") },
    )

    @Test
    fun recordsAppendedWhileAFoldRunsFollowItsCounts() = assertRows(backends, { it.first }) { appendsWhileFolding(it.second()) }

    private fun appendsWhileFolding(file: LogFile) {
        val folding = CountDownLatch(1)
        val appended = CountDownLatch(1)
        var folded: ByteArray? = null
        // what the fold's own thread saw, asserted on the test's: a failure there would only reach the error sink
        val released = AtomicBoolean(false)
        val threads = Threads()
        val errors = ArrayList<IOException>()
        // the log folded to its counts on the compaction's thread, held there till the appends are done
        val fold = RecordStore.Fold { log, to ->
            folded = log
            folding.countDown()
            released.set(appended.await(10, TimeUnit.SECONDS))
            to.write(format.header())
            words(log).forEach { (w, n) -> to.write(count(w, n)) }
            true
        }
        val kept = LinkedHashMap<String, Int>()
        val store = RecordStore(file, format, 40, { synchronized(errors) { errors += it } }, background = threads, fold = fold)
        store.open(replay = { type, input -> replay(type, input, kept) }, counts = { write -> kept.forEach { (w, n) -> write(count(w, n)) } })
        // past 40 bytes: the fold is due, and runs on its own thread
        repeat(3) { store.append(added("a")) }
        assertTrue(folding.await(10, TimeUnit.SECONDS))
        val since = ByteArrayOutputStream()
        for (i in 0 until APPENDED) {
            val record = added("w$i")
            assertTrue(store.append(record))
            since.write(record)
        }
        appended.countDown()
        threads.join()
        assertTrue("the fold was let go by the appends", released.get())
        // the next append puts the counts in place, what was appended meanwhile behind them
        store.append(added("last"))
        since.write(added("last"))
        store.close()

        assertArrayEquals("the fold read the log as it was when due", format.header() + added("a") + added("a") + added("a"), folded)
        assertArrayEquals(format.header() + count("a", 3) + since.toByteArray(), file.read())
        assertFalse(file.beside(".compacting").exists())
        assertEquals(emptyList<IOException>(), errors)
        assertEquals(1, threads.started.size)
        val read = words(file.read())
        assertEquals(3, read["a"])
        assertEquals(APPENDED + 2, read.size)
    }

    // the counts in memory written on the compaction's thread while the learning one appends on
    @Test
    fun recordsAppendedWhileCountsAreWrittenFollowThem() = assertRows(backends, { it.first }) { appendsWhileCounting(it.second()) }

    private fun appendsWhileCounting(file: LogFile) {
        val threads = Threads()
        val errors = ArrayList<IOException>()
        val kept = LinkedHashMap<String, Int>()
        val store = RecordStore(file, format, 40, { synchronized(errors) { errors += it } }, background = threads)
        store.open(replay = { type, input -> replay(type, input, kept) }, counts = { write -> kept.forEach { (w, n) -> write(count(w, n)) } })
        fun learn(word: String) {
            kept[word] = (kept[word] ?: 0) + 1
            store.append(added(word))
        }
        repeat(3) { learn("a") }
        // appended as the thread writes, however far it has got
        for (i in 0 until APPENDED) learn("w${i % 7}")
        threads.join()
        learn("last")
        store.close()
        assertFalse(file.beside(".compacting").exists())
        assertEquals(kept, words(file.read()))
        assertEquals(emptyList<IOException>(), errors)
    }

    private companion object {
        const val ADDED: Byte = 1
        const val COUNT: Byte = 2
        const val APPENDED = 200
    }
}
