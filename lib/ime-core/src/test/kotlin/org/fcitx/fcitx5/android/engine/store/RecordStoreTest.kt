/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.store

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** The bytes of a [RecordStore]'s log, over a format of words counted. */
class RecordStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val file get() = File(tmp.root, "words.log")
    private val aside get() = File(file.path + RecordStore.UNREADABLE)
    private val format = RecordFormat("TEST", 1)

    private fun added(word: String) = format.record(ADDED) { writeUTF(word) }

    private fun count(word: String, n: Int) = format.record(COUNT) {
        writeUTF(word)
        writeInt(n)
    }

    /** A record whose fields are [length] bytes long. */
    private fun padding(length: Int) = format.record(PADDING) { write(ByteArray(length - 1)) }

    private fun replay(type: Byte, input: DataInputStream, into: MutableMap<String, Int>) {
        when (type) {
            ADDED -> {
                val word = input.readUTF()
                into[word] = (into[word] ?: 0) + 1
            }
            COUNT -> into[input.readUTF()] = input.readInt()
        }
    }

    /** A store of [file], opened: its log replayed into [kept], which it compacts to. */
    private fun store(
        kept: MutableMap<String, Int>,
        errors: MutableList<IOException> = ArrayList(),
        compactAt: Long = RecordStore.DEFAULT_COMPACT_AT,
        background: Executor? = null,
        openAppend: (File) -> OutputStream = { FileOutputStream(it, true) },
        fold: RecordStore.Fold? = null,
    ) = RecordStore(file, format, compactAt, { errors += it }, openAppend, background, fold).apply {
        open(replay = { type, input -> replay(type, input, kept) }, counts = { write -> kept.forEach { (word, n) -> write(count(word, n)) } })
    }

    /** What the log holds, as the next start reads it. */
    private fun read(errors: MutableList<IOException> = ArrayList()): Map<String, Int> =
        HashMap<String, Int>().also { store(it, errors).close() }

    private fun RecordStore.learn(kept: MutableMap<String, Int>, word: String) {
        kept[word] = (kept[word] ?: 0) + 1
        append(added(word))
    }

    @Test
    fun aRecordIsWrittenIfAReaderTakesItAndOtherwiseSaidSo() {
        val errors = ArrayList<IOException>()
        val kept = HashMap<String, Int>()
        store(kept, errors).use { s ->
            // fields as long as a reader takes, then one byte longer
            s.append(padding(RecordFormat.MAX_RECORD))
            s.append(padding(RecordFormat.MAX_RECORD + 1))
            s.learn(kept, "a")
        }
        assertEquals(1, errors.size)
        assertEquals(8L + padding(RecordFormat.MAX_RECORD).size + added("a").size, file.length())
        val reading = ArrayList<IOException>()
        assertEquals(mapOf("a" to 1), read(reading))
        assertEquals(emptyList<IOException>(), reading)
    }

    // open reads past the type that marks a log emptied: a format's record of it would be lost unsaid
    @Test
    fun aRecordOfTheTypeThatMarksALogEmptiedIsNotWrittenAndSaidSo() {
        val errors = ArrayList<IOException>()
        val kept = HashMap<String, Int>()
        store(kept, errors).use { s ->
            s.append(format.record(0) { writeUTF("x") })
            s.learn(kept, "a")
        }
        assertEquals(1, errors.size)
        assertArrayEquals(format.header() + added("a"), file.readBytes())
        assertEquals(mapOf("a" to 1), read())
    }

    // an import's words in one write: the same bytes as one at a time, one that does not fit left out
    @Test
    fun recordsAppendedAllAtOnceAreTheLogOneAtATimeWouldBe() {
        val errors = ArrayList<IOException>()
        val kept = HashMap<String, Int>()
        store(kept, errors).use { s ->
            s.appendAll(listOf(added("a"), format.record(0) { writeUTF("x") }, added("b")))
        }
        assertEquals(1, errors.size)
        assertArrayEquals(format.header() + added("a") + added("b"), file.readBytes())
        assertEquals(mapOf("a" to 1, "b" to 1), read())
    }

    @Test
    fun aCountTooLongForAReaderIsLeftOutOfTheCompactedLogAndSaidSo() {
        val errors = ArrayList<IOException>()
        val kept = LinkedHashMap<String, Int>()
        store(kept, errors).use { s ->
            // in memory alone, before a count that must not be lost with it
            kept["x".repeat(65535)] = 1
            s.learn(kept, "a")
            s.compact()
        }
        assertEquals(1, errors.size)
        assertEquals(mapOf("a" to 1), read())
        assertFalse(aside.exists())
    }

    @Test
    fun aRecordGarbledAfterGoodOnesIsKeptAsideAndCutWithoutMarkingTheLogEmptied() {
        val good = added("a")
        val garbled = added("b").also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        // the last record whole, and one with another after it
        for (tail in listOf(garbled, garbled + added("c"))) {
            val damaged = format.header() + good + tail
            file.writeBytes(damaged)
            aside.delete()
            val errors = ArrayList<IOException>()
            assertEquals(mapOf("a" to 1), read(errors))
            assertEquals(1, errors.size)
            assertArrayEquals(damaged, aside.readBytes())
            assertArrayEquals(format.header() + good, file.readBytes())
        }
    }

    /**
     * A torn append leaves a last record whose length, or the bytes that length says, run past
     * the end; a length no record has is garbage even when the bytes run out.
     */
    @Test
    fun aTornAppendIsCutWithoutAWordButALengthPastWhatAppendWritesIsDamage() {
        val good = added("a")
        val longest = padding(RecordFormat.MAX_RECORD)
        // killed in the length, in a record, and in the longest record a reader takes
        for (tail in listOf(good.copyOf(2), good.copyOf(good.size - 1), longest.copyOf(longest.size - 1))) {
            file.writeBytes(format.header() + good + tail)
            val errors = ArrayList<IOException>()
            assertEquals(mapOf("a" to 1), read(errors))
            assertEquals(emptyList<IOException>(), errors)
            assertArrayEquals(format.header() + good, file.readBytes())
        }
        assertFalse(aside.exists())
        file.writeBytes(format.header() + good + ByteBuffer.allocate(8).putInt(RecordFormat.MAX_RECORD + 1).array())
        val errors = ArrayList<IOException>()
        assertEquals(mapOf("a" to 1), read(errors))
        assertEquals(1, errors.size)
        assertTrue(aside.exists())
    }

    /**
     * Runs what it is given only when told to, on the thread that tells it. So no task is still
     * being written by another thread when the store waits for it (at close, or an explicit
     * compaction): that wait is not exercised here, as the store has no seam to hold a write.
     */
    private class Later : Executor {
        val tasks = ArrayList<Runnable>()

        override fun execute(command: Runnable) {
            tasks += command
        }

        fun run() = tasks.forEach { it.run() }
    }

    @Test
    fun aCompactionAnAppendMadeDueIsWrittenByTheExecutorAndPutInPlaceByTheNextAppend() {
        val later = Later()
        val kept = LinkedHashMap<String, Int>()
        store(kept, compactAt = 40, background = later).use { s ->
            // past 40 bytes: the counts are read, and the log goes on as it was
            repeat(3) { s.learn(kept, "a") }
            assertEquals(1, later.tasks.size)
            s.learn(kept, "a")
            later.run()
            assertEquals(8L + 4 * added("a").size, file.length())
            s.learn(kept, "a")
            // what was appended meanwhile follows the counts
            assertArrayEquals(format.header() + count("a", 3) + added("a") + added("a"), file.readBytes())
            assertFalse(File(file.path + ".compacting").exists())
        }
        assertEquals(mapOf("a" to 5), read())
    }

    @Test
    fun aCompactionNoThreadHasWrittenAtCloseIsWrittenThenAndPutInPlace() {
        val kept = LinkedHashMap<String, Int>()
        store(kept, compactAt = 40, background = Later()).use { s -> repeat(3) { s.learn(kept, "a") } }
        assertArrayEquals(format.header() + count("a", 3), file.readBytes())
        assertEquals(mapOf("a" to 3), read())
    }

    @Test
    fun aCompactionThatFailsInTheBackgroundIsSaidSoAndTheLogKept() {
        val later = Later()
        val errors = ArrayList<IOException>()
        val kept = LinkedHashMap<String, Int>()
        // where the counts would be written, something that cannot be
        File(file.path + ".compacting").mkdirs()
        store(kept, errors, compactAt = 40, background = later).use { s ->
            repeat(3) { s.learn(kept, "a") }
            later.run()
            assertEquals("heard of by the store, at its next append", 0, errors.size)
            s.learn(kept, "a")
            assertEquals(1, errors.size)
            // not tried again before the log has doubled
            s.learn(kept, "a")
            assertEquals(1, later.tasks.size)
        }
        assertEquals(8L + 5 * added("a").size, file.length())
        assertEquals(mapOf("a" to 5), read())
    }

    // a seed that seeded nothing (its source did not read) leaves a header alone, seeded again at the next open
    @Test
    fun aSeedThatSeededNothingIsTriedAgainAtTheNextOpen() {
        val kept = LinkedHashMap<String, Int>()
        val counts: ((ByteArray) -> Unit) -> Unit = { write -> kept.forEach { (word, n) -> write(count(word, n)) } }
        var seeds = 0
        RecordStore(file, format, RecordStore.DEFAULT_COMPACT_AT, {}).use {
            it.open(replay = { type, input -> replay(type, input, kept) }, counts = counts, seed = { seeds++; false })
        }
        assertArrayEquals(format.header(), file.readBytes())
        RecordStore(file, format, RecordStore.DEFAULT_COMPACT_AT, {}).use {
            it.open(replay = { type, input -> replay(type, input, kept) }, counts = counts, seed = { seeds++; kept["a"] = 3; true })
        }
        assertEquals(2, seeds)
        assertArrayEquals(format.header() + count("a", 3), file.readBytes())
    }

    @Test
    fun aFoldWithNothingToFoldLeavesTheLogAsItWasAndIsNotDueAgainAtTheNextAppend() {
        val later = Later()
        val errors = ArrayList<IOException>()
        val kept = LinkedHashMap<String, Int>()
        store(kept, errors, compactAt = 40, background = later, fold = { _, _ -> false }).use { s ->
            repeat(3) { s.learn(kept, "a") }
            assertEquals(1, later.tasks.size)
            later.run()
            s.learn(kept, "a")
            assertArrayEquals(format.header() + added("a") + added("a") + added("a") + added("a"), file.readBytes())
            assertFalse(File(file.path + ".compacting").exists())
            // compacted marked at the end: the next appends do not read the whole log again
            s.learn(kept, "a")
            assertEquals(1, later.tasks.size)
        }
        assertEquals(0, errors.size)
        assertEquals(mapOf("a" to 5), read())
    }

    @Test
    fun aRewriteToAHeaderAloneIsDeclinedAndSaidSo() {
        val errors = ArrayList<IOException>()
        val kept = LinkedHashMap<String, Int>()
        store(kept, errors).use { s ->
            s.learn(kept, "a")
            val before = file.readBytes()
            assertEquals(null, s.rewrite { _, out -> out.write(format.header()); true })
            assertEquals(1, errors.size)
            assertArrayEquals(before, file.readBytes())
            // still open: what is learned next is appended
            s.learn(kept, "b")
        }
        assertEquals(mapOf("a" to 1, "b" to 1), read())
    }

    @Test
    fun anExecutorThatRefusesLeavesTheCompactionToTheThreadThatLearns() {
        val kept = LinkedHashMap<String, Int>()
        store(kept, compactAt = 40, background = Executor { throw RejectedExecutionException() }).use { s ->
            repeat(4) { s.learn(kept, "a") }
            assertArrayEquals(format.header() + count("a", 3) + added("a"), file.readBytes())
        }
    }

    /** As after a forget: compacted at once while counts read before it are still to be written. */
    @Test
    fun anExplicitCompactionFirstPutsOneInTheBackgroundInPlaceAndIsNotUndoneByIt() {
        val later = Later()
        val next = File(file.path + ".compacting")
        val kept = LinkedHashMap<String, Int>()
        store(kept, compactAt = 40, background = later).use { s ->
            repeat(3) { s.learn(kept, "a") }
            s.learn(kept, "b")
            kept.remove("a")
            s.compact()
            assertArrayEquals(format.header() + count("b", 1), file.readBytes())
            assertFalse(next.exists())
            // written already: it writes nothing now, and nothing older is put in place after
            later.run()
            assertFalse(next.exists())
            s.learn(kept, "b")
            assertArrayEquals(format.header() + count("b", 1) + added("b"), file.readBytes())
        }
        assertEquals(mapOf("b" to 2), read())
    }

    /**
     * Killed once the counts were synced to `.compacting` but before they were put in place: the
     * log is still whole, and the next open compacts it again in the background, over that file.
     */
    @Test
    fun aCompactionKilledBeforeItWasPutInPlaceIsDoneAgainInTheBackgroundAtTheNextOpen() {
        val first = Later()
        val streams = ArrayList<OutputStream>()
        val kept = LinkedHashMap<String, Int>()
        val killed = store(kept, compactAt = 40, background = first, openAppend = { FileOutputStream(it, true).also { s -> streams += s } })
        repeat(3) { killed.learn(kept, "a") }
        killed.learn(kept, "b")
        first.run()
        val next = File(file.path + ".compacting")
        assertArrayEquals(format.header() + count("a", 3), next.readBytes())
        // no append nor close puts it in place: the system closes the files
        streams.forEach { it.close() }
        val log = format.header() + added("a") + added("a") + added("a") + added("b")
        assertArrayEquals(log, file.readBytes())
        val later = Later()
        val reopened = LinkedHashMap<String, Int>()
        store(reopened, compactAt = 40, background = later).use { s ->
            assertEquals(mapOf("a" to 3, "b" to 1), reopened)
            // due, but not written by the thread that opens
            assertArrayEquals(log, file.readBytes())
            assertEquals(1, later.tasks.size)
            later.run()
            s.learn(reopened, "b")
            assertArrayEquals(format.header() + count("a", 3) + count("b", 1) + added("b"), file.readBytes())
            assertFalse(next.exists())
        }
        assertEquals(mapOf("a" to 3, "b" to 2), read())
    }

    @Test
    fun countsWrittenInTheBackgroundButGoneWhenPutInPlaceAreSaidSoAndTheLogKept() {
        val later = Later()
        val errors = ArrayList<IOException>()
        val next = File(file.path + ".compacting")
        val kept = LinkedHashMap<String, Int>()
        store(kept, errors, compactAt = 40, background = later).use { s ->
            repeat(3) { s.learn(kept, "a") }
            later.run()
            assertTrue(next.delete())
            s.learn(kept, "a")
            assertEquals(1, errors.size)
            assertFalse(next.exists())
            assertArrayEquals(format.header() + added("a") + added("a") + added("a") + added("a"), file.readBytes())
        }
        assertEquals(mapOf("a" to 4), read())
    }

    /**
     * A copy aside, or counts compacted in the background, whose write fails once the file is
     * made (the disk full, as /dev/full is) leaves no file cut short behind. Only the background
     * write is made to fail here: aside() never picks a name that exists, so cut()'s copy aside,
     * which goes through the same writeSynced, cannot be pointed at /dev/full. Skipped where there
     * is no /dev/full to link to (macOS, Windows).
     */
    @Test
    fun aFileWhoseWriteFailsIsNotLeftBehind() {
        val next = File(file.path + ".compacting")
        assumeTrue(runCatching { Files.createSymbolicLink(next.toPath(), File("/dev/full").toPath()) }.isSuccess && next.exists())
        val later = Later()
        val errors = ArrayList<IOException>()
        val kept = LinkedHashMap<String, Int>()
        store(kept, errors, compactAt = 40, background = later).use { s ->
            repeat(3) { s.learn(kept, "a") }
            later.run()
            s.learn(kept, "a")
        }
        assertEquals(1, errors.size)
        assertFalse(Files.exists(next.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals(mapOf("a" to 4), read())
    }

    @Test
    fun aStoreThatStoppedTakingWritesAfterAFailedOneDoesNotCompactNorThrow() {
        val errors = ArrayList<IOException>()
        val kept = HashMap<String, Int>()
        var opens = 0
        // the first stream fails its write, and the file cannot be opened again to go on after it
        val disk: (File) -> OutputStream = {
            if (opens++ > 0) throw IOException("cannot open")
            object : OutputStream() {
                override fun write(b: Int) {
                    throw IOException("disk full")
                }
            }
        }
        store(kept, errors, openAppend = disk).use { s ->
            s.learn(kept, "a")
            s.compact()
        }
        assertEquals(listOf("disk full", "cannot open"), errors.map { it.message })
        assertArrayEquals(format.header(), file.readBytes())
    }

    private companion object {
        const val ADDED: Byte = 1
        const val COUNT: Byte = 2
        const val PADDING: Byte = 3
    }
}
