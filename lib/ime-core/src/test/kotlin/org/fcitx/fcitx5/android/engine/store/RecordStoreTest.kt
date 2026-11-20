/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.store

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.platform.LogFile
import org.fcitx.fcitx5.android.engine.platform.LogOutput
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    ) = RecordStore(JvmFile(file), format, compactAt, { errors += it }, { openAppend((it as JvmFile).file) }, background, fold).apply {
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

    // one record that does not fit is left out, so not all are written
    @Test
    fun recordsAppendedAllAtOnceAreTheLogOneAtATimeWouldBe() {
        val errors = ArrayList<IOException>()
        val kept = HashMap<String, Int>()
        store(kept, errors).use { s ->
            assertFalse(s.appendAll(listOf(added("a"), format.record(0) { writeUTF("x") }, added("b"))))
            assertTrue(s.appendAll(listOf(added("c"))))
        }
        assertEquals(1, errors.size)
        assertArrayEquals(format.header() + added("a") + added("b") + added("c"), file.readBytes())
        assertEquals(mapOf("a" to 1, "b" to 1, "c" to 1), read())
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
    fun aLastRecordGarbledAfterGoodOnesIsKeptAsideAndCutWithoutMarkingTheLogEmptied() {
        val good = added("a")
        val damaged = format.header() + good + garbled("b")
        file.writeBytes(damaged)
        val errors = ArrayList<IOException>()
        assertEquals(mapOf("a" to 1), read(errors))
        assertTrue(errors.single() is RecordStore.KeptAside)
        assertArrayEquals(damaged, aside.readBytes())
        assertArrayEquals(format.header() + good, file.readBytes())
    }

    @Uat("WD-47")
    @Test
    fun recordsGarbledMidLogAreDroppedAndTheRestReadOn() = assertRows(
        listOf(
            GarbledRow("one garbled record, whole to the end after it", added("a") + garbled("b") + added("d"), listOf("a", "d"), stops = false),
            GarbledRow("a run of garbled records", added("a") + garbled("b") + garbled("c") + added("d"), listOf("a", "d"), stops = false),
            // the log ends past the last whole record, so it is rewritten up to there, not cut
            GarbledRow(
                "a torn append after: cut with the rewrite, and no damage of its own to tell",
                added("a") + garbled("b") + added("c") + added("d").let { it.copyOf(it.size - 1) },
                listOf("a", "c"),
                stops = false,
            ),
            GarbledRow(
                "a garbled last record after, nothing whole behind it: reading stops there, told with the rest",
                added("a") + garbled("b") + added("c") + garbled("d"),
                listOf("a", "c"),
                stops = true,
            ),
        ),
        { it.why },
    ) { row ->
        tmp.root.listFiles()!!.forEach { it.delete() }
        val damaged = format.header() + row.records
        file.writeBytes(damaged)
        val errors = ArrayList<IOException>()
        assertEquals(row.read.associateWith { 1 }, read(errors))
        assertTrue(errors.single() is RecordStore.KeptAside)
        assertEquals(errors.single().message, row.stops, "stops reading" in errors.single().message.orEmpty())
        assertArrayEquals(damaged, aside.readBytes())
        assertArrayEquals(row.read.fold(format.header()) { log, word -> log + added(word) }, file.readBytes())
        val again = ArrayList<IOException>()
        assertEquals(row.read.associateWith { 1 }, read(again))
        assertEquals(emptyList<IOException>(), again)
        assertFalse(File(file.path + RecordStore.UNREADABLE + ".1").exists())
    }

    /** A log's [records] after its header with garbled ones among them, the words that [read] of it, and whether it [stops] reading before its end other than in a torn append. */
    private class GarbledRow(val why: String, val records: ByteArray, val read: List<String>, val stops: Boolean)

    @Test
    fun aReaderNotToldOfGarbledRecordsStopsAtTheFirst() {
        val bytes = format.header() + added("a") + garbled("b") + added("d")
        val stopped = ArrayList<String>()
        assertEquals((format.header() + added("a")).size, format.read(bytes) { _, input -> stopped += input.readUTF() })
        assertEquals(listOf("a"), stopped)
        val past = ArrayList<String>()
        val dropped = ArrayList<IntRange>()
        assertEquals(bytes.size, format.read(bytes, garbled = { from, to -> dropped += from until to }) { _, input -> past += input.readUTF() })
        assertEquals(listOf("a", "d"), past)
        assertEquals(listOf((format.header() + added("a")).size until (bytes.size - added("d").size)), dropped)
    }

    @Uat("WD-47")
    @Test
    fun aLogThatIsNotOneThisReadsIsKeptAsideAndSaidSo() {
        file.writeBytes("not a log".toByteArray())
        val errors = ArrayList<IOException>()
        assertEquals(emptyMap<String, Int>(), read(errors))
        assertTrue(errors.single() is RecordStore.KeptAside)
        assertArrayEquals("not a log".toByteArray(), aside.readBytes())
    }

    // engine-07: the one rule for the names of the copies kept aside, which the app and the clears ask
    @Test
    fun theCopiesKeptAsideAreKnownByName() {
        for (name in listOf("learn.log.unreadable", "learn.log.unreadable.1", "learn.log.unreadable.12")) {
            assertTrue(name, RecordStore.isAside("learn.log", name))
        }
        for (name in listOf("learn.log", "learn.log.unreadable.", "learn.log.unreadable.x", "learn.log.unreadable.cleared", "words.log.unreadable")) {
            assertFalse(name, RecordStore.isAside("learn.log", name))
        }
    }

    // a record whose CRC fails, its length sound
    private fun garbled(word: String) = added(word).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

    /** A torn append's last record runs past the end; a length no record has is garbage even then. */
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

    /** Runs what it is given only when told to, on the telling thread; the store's wait for a task is not exercised here. */
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
        File(file.path + ".compacting").mkdirs()
        store(kept, errors, compactAt = 40, background = later).use { s ->
            repeat(3) { s.learn(kept, "a") }
            later.run()
            assertEquals("heard of by the store, at its next append", 0, errors.size)
            s.learn(kept, "a")
            assertEquals(1, errors.size)
            s.learn(kept, "a")
            assertEquals(1, later.tasks.size)
        }
        assertEquals(8L + 5 * added("a").size, file.length())
        assertEquals(mapOf("a" to 5), read())
    }

    @Test
    fun aSeedThatSeededNothingIsTriedAgainAtTheNextOpen() {
        val kept = LinkedHashMap<String, Int>()
        val counts: ((ByteArray) -> Unit) -> Unit = { write -> kept.forEach { (word, n) -> write(count(word, n)) } }
        var seeds = 0
        RecordStore(JvmFile(file), format, RecordStore.DEFAULT_COMPACT_AT, {}).use {
            it.open(replay = { type, input -> replay(type, input, kept) }, counts = counts, seed = { seeds++; false })
        }
        assertArrayEquals(format.header(), file.readBytes())
        RecordStore(JvmFile(file), format, RecordStore.DEFAULT_COMPACT_AT, {}).use {
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

    // must not leave the log in the file while the user is told it was cleared
    @Test
    fun aRewriteToAHeaderAloneIsWrittenAsALogEmptied() {
        val errors = ArrayList<IOException>()
        val kept = LinkedHashMap<String, Int>()
        val emptied = format.header() + format.record(0) {}
        store(kept, errors).use { s ->
            s.learn(kept, "a")
            assertArrayEquals(emptied, s.rewrite { _, out -> out.write(format.header()); true })
            assertArrayEquals(emptied, file.readBytes())
            assertTrue(s.writing)
        }
        assertEquals(emptyList<IOException>(), errors)
        assertEquals(emptyMap<String, Int>(), read())
        var seeds = 0
        RecordStore(JvmFile(file), format, RecordStore.DEFAULT_COMPACT_AT, {}).use {
            it.open(replay = { _, _ -> }, counts = { }, seed = { seeds++; true })
            // still a log: what is learned next is appended behind the mark
            it.append(added("b"))
        }
        assertEquals(0, seeds)
        assertEquals(mapOf("b" to 1), read())
    }

    @Test
    fun aRewriteToLessThanAHeaderIsDeclinedAndSaidSo() {
        val errors = ArrayList<IOException>()
        val kept = LinkedHashMap<String, Int>()
        store(kept, errors).use { s ->
            s.learn(kept, "a")
            val before = file.readBytes()
            assertEquals(null, s.rewrite { _, _ -> true })
            assertEquals(1, errors.size)
            assertArrayEquals(before, file.readBytes())
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

    /** A copy aside or compaction whose write fails (as on /dev/full) leaves no file cut short. Skipped where there is no /dev/full. */
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
            assertTrue(s.writing)
            s.learn(kept, "a")
            // decode-4: what the word list and the status panel say for as long as it lasts
            assertFalse(s.writing)
            s.compact()
        }
        assertEquals(listOf("disk full", "cannot open"), errors.map { it.message })
        assertArrayEquals(format.header(), file.readBytes())
    }

    /** A full disk refuses writes after a reopen too: not writing while it lasts, writing again once an append goes through. */
    @Uat("WD-46")
    @Test
    fun aStoreWhoseWritesKeepFailingIsNotWritingUntilOneIsWritten() = assertRows(
        listOf<Pair<String, (RecordStore) -> Boolean>>(
            "one record appended" to { it.append(added("c")) },
            "records appended all at once" to { it.appendAll(listOf(added("c"))) },
        ),
        { it.first },
    ) { (_, append) ->
        file.delete()
        val errors = ArrayList<IOException>()
        val kept = HashMap<String, Int>()
        var full = false
        var opens = 0
        val disk: (File) -> OutputStream = {
            opens++
            val real = FileOutputStream(it, true)
            object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (full) throw IOException("disk full")
                    real.write(b, off, len)
                }

                override fun close() = real.close()
            }
        }
        store(kept, errors, openAppend = disk).use { s ->
            s.learn(kept, "a")
            assertTrue(s.writing)
            full = true
            s.learn(kept, "b")
            assertEquals("opened again after the write refused", 2, opens)
            assertFalse("opened again, the disk still full", s.writing)
            assertFalse(append(s))
            assertFalse("the next write refused too", s.writing)
            assertEquals(listOf("disk full", "disk full"), errors.map { e -> e.message })
            full = false
            assertTrue(append(s))
            assertTrue("a write went through", s.writing)
        }
        assertArrayEquals(format.header() + added("a") + added("c"), file.readBytes())
    }

    @Uat("WD-46")
    @Test
    fun aStoreAWriteWasRefusedIsWritingAgainOnceTheLogIsWrittenWhole() = assertRows(
        listOf<Triple<String, RecordStore.Fold?, (RecordStore) -> Unit>>(
            Triple("rewritten as the user asked (a clear)", null) { s -> assertTrue(s.rewrite { bytes, out -> out.write(bytes); true } != null) },
            Triple("compacted to the counts", null) { s -> s.compact() },
            Triple("compacted from its own bytes (the learning record's fold)", RecordStore.Fold { log, to -> to.write(log); true }) { s -> s.compact() },
        ),
        { it.first },
    ) { (why, fold, whole) ->
        file.delete()
        val errors = ArrayList<IOException>()
        val kept = HashMap<String, Int>()
        var full = false
        // appends alone are refused: what writes the log whole goes to a file beside it, renamed over
        val disk: (File) -> OutputStream = {
            val real = FileOutputStream(it, true)
            object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (full) throw IOException("disk full")
                    real.write(b, off, len)
                }

                override fun close() = real.close()
            }
        }
        store(kept, errors, openAppend = disk, fold = fold).use { s ->
            s.learn(kept, "a")
            full = true
            s.append(added("b"))
            assertFalse(why, s.writing)
            full = false
            whole(s)
            assertTrue(why, s.writing)
            assertTrue(why, s.append(added("c")))
        }
        assertEquals(why, listOf("disk full"), errors.map { e -> e.message })
        assertEquals(why, mapOf("a" to 1, "c" to 1), read())
    }

    /** [real], but [atSync] runs before a whole-file write is synced: the seam to stop a compaction there. */
    private class SyncHeld(private val real: JvmFile, private val atSync: () -> Unit) : LogFile by real {
        override fun beside(suffix: String): LogFile = SyncHeld(real.beside(suffix), atSync)

        override fun renameTo(dest: LogFile) = real.renameTo((dest as SyncHeld).real)

        override fun create(): LogOutput {
            val out = real.create()
            return object : LogOutput() {
                override fun write(b: Int) = out.write(b)

                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

                override fun sync() {
                    atSync()
                    out.sync()
                }

                override fun close() = out.close()
            }
        }

        override fun toString() = real.toString()
    }

    /** A store with a compaction due, its file for [later] to write, [whole] once all written, and the disk about to refuse an append. */
    private class Refusal(val s: RecordStore, val later: Later, val whole: ByteArray, val refuse: () -> Unit) {
        /** Run once, as the compaction's file is about to be synced: every byte of it written, its task not ended. */
        var atSync: () -> Unit = {}
    }

    private class RefusalRow(val why: String, val writing: Boolean, val since: List<String> = emptyList(), val steps: Refusal.() -> Unit)

    @Uat("WD-46")
    @Test
    fun aCompactionPutInPlaceAfterAWriteRefusedIsWritingOnlyIfWrittenSince() {
        val rows = listOf(
            RefusalRow("written before the refusal, nothing appended since: it tells nothing of the disk now", writing = false) {
                later.run()
                refuse()
            },
            // a compaction written but not yet synced is not written after the refusal
            RefusalRow("every byte of it written at the refusal, its sync to come and its task not ended: as the row above", writing = false) {
                var writtenAtRefusal = -1L
                atSync = {
                    writtenAtRefusal = File(file.path + ".compacting").length()
                    refuse()
                }
                later.run()
                assertEquals("refused once all of it was written, the task not ended", whole.size.toLong(), writtenAtRefusal)
            },
            RefusalRow("written before it, a record appended before it written behind them after", writing = true, since = listOf("a")) {
                assertTrue(s.append(added("a")))
                later.run()
                refuse()
            },
            RefusalRow("not yet written at the refusal: written whole after it, as it is put in place", writing = true) { refuse() },
        )
        // what each compaction leaves of a thrice learned: its count, or the log as it was (a fold that keeps every record)
        val folds = listOf<Triple<String, RecordStore.Fold?, ByteArray>>(
            Triple("the counts", null, format.header() + count("a", 3)),
            Triple("the log folded (the learning record's)", RecordStore.Fold { log, to -> to.write(log); true }, format.header() + added("a") + added("a") + added("a")),
        )
        assertRows(folds.flatMap { kind -> rows.map { kind to it } }, { (kind, row) -> "${kind.first}, ${row.why}" }) { (kind, row) ->
            val why = "${kind.first}, ${row.why}"
            file.delete()
            val errors = ArrayList<IOException>()
            val kept = LinkedHashMap<String, Int>()
            val later = Later()
            var full = false
            // appends alone are refused: the compaction goes to a file beside the log, renamed over it
            val disk: (LogFile) -> OutputStream = {
                val real = it.append()
                object : OutputStream() {
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        if (full) throw IOException("disk full")
                        real.write(b, off, len)
                    }

                    override fun close() = real.close()
                }
            }
            var atSync: () -> Unit = {}
            RecordStore(SyncHeld(JvmFile(file)) { atSync() }, format, 40, { errors += it }, disk, later, kind.second).use { s ->
                s.open(replay = { type, input -> replay(type, input, kept) }, counts = { write -> kept.forEach { (word, n) -> write(count(word, n)) } })
                repeat(3) { s.learn(kept, "a") }
                assertEquals(why, 1, later.tasks.size)
                val refusal = Refusal(s, later, kind.third) {
                    full = true
                    assertFalse(why, s.append(added("b")))
                    full = false
                }
                atSync = {
                    atSync = {}
                    refusal.atSync()
                }
                row.steps(refusal)
                assertFalse(why, s.writing)
                assertEquals(why, null, s.rewrite { _, _ -> false })
                assertFalse(why, File(file.path + ".compacting").exists())
                assertArrayEquals(why, row.since.fold(kind.third) { bytes, word -> bytes + added(word) }, file.readBytes())
                assertEquals(why, row.writing, s.writing)
                assertTrue(why, s.append(added("c")))
                assertTrue(why, s.writing)
            }
            assertEquals(why, listOf("disk full"), errors.map { e -> e.message })
        }
    }

    /** A rewrite of a log that takes no writes throws, else a clear was told done with what it cleared still in the file. */
    @Uat("WD-46")
    @Test
    fun aRewriteOfALogNotOpenedAgainBehindItsCompactionThrowsAndWritesNothing() {
        val errors = ArrayList<IOException>()
        val kept = LinkedHashMap<String, Int>()
        val later = Later()
        var refuseOpens = false
        val disk: (File) -> OutputStream = { if (refuseOpens) throw IOException("not opened") else FileOutputStream(it, true) }
        store(kept, errors, compactAt = 40, background = later, openAppend = disk).use { s ->
            repeat(3) { s.learn(kept, "a") }
            assertEquals(1, later.tasks.size)
            later.run()
            assertTrue("taking writes till the compaction is put in place", s.writing)
            refuseOpens = true
            var asked = false
            val rewrite = { s.rewrite { _, out -> asked = true; out.write(format.header()); true } }
            assertThrows(IOException::class.java) { rewrite() }
            assertFalse("the log was not read to be rewritten", asked)
            assertFalse(s.writing)
            assertFalse(File(file.path + ".compacting").exists())
            assertArrayEquals(format.header() + count("a", 3), file.readBytes())
            // and so from then on, what [checkRewrites] tells a caller before it deletes anything
            assertThrows(IOException::class.java) { rewrite() }
            assertThrows(IOException::class.java) { s.checkRewrites() }
            assertFalse(asked)
        }
        assertEquals(listOf("not opened"), errors.map { e -> e.message })
        assertArrayEquals(format.header() + count("a", 3), file.readBytes())
    }

    private companion object {
        const val ADDED: Byte = 1
        const val COUNT: Byte = 2
        const val PADDING: Byte = 3
    }
}
