/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.user.UserModelTest.Companion.entry
import org.fcitx.fcitx5.android.engine.user.UserModelTest.Companion.model
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer

class UserStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val file get() = File(tmp.root, "user.log")

    private fun counts(m: UserModel): Map<String, Float> {
        val out = HashMap<String, Float>()
        m.forEachCount({ e, c -> out[e.toString()] = c }, { a, b, c -> out["$a $b"] = c })
        return out
    }

    /** Opens a store on [file] over a new model, runs [block], and closes it. */
    private fun session(compactAt: Long = UserStore.DEFAULT_COMPACT_AT, block: (UserModel, UserStore) -> Unit = { _, _ -> }): UserModel {
        val m = model()
        UserStore(file, m, compactAt).use {
            it.open()
            block(m, it)
        }
        return m
    }

    @Test
    fun whatIsLearnedIsThereNextTime() {
        val first = session { m, _ ->
            m.learn(null, listOf(entry("你", "ni"), entry("拟好", "ni", "hao")))
            m.learn(entry("拟好", "ni", "hao"), listOf(entry("吗", "ma")))
        }
        val second = session()
        assertEquals(counts(first), counts(second))
        assertEquals(1, second.size)
        assertEquals("拟好", second.text(second.id(entry("拟好", "ni", "hao"))))
    }

    @Test
    fun aRecordCutShortIsDroppedAndCutFromTheFile() {
        session { m, _ ->
            m.learn(null, listOf(entry("你", "ni")))
            m.learn(null, listOf(entry("好", "hao")))
        }
        val whole = file.length()
        // killed while writing the second record
        file.writeBytes(file.readBytes().copyOf(whole.toInt() - 3))
        val m = session { m, _ -> m.learn(null, listOf(entry("吗", "ma"))) }
        assertEquals(mapOf("你(ni)" to 1f, "吗(ma)" to 1f), counts(m))
        // appends went on from the last whole record: all of it reads
        assertEquals(counts(m), counts(session()))
    }

    @Test
    fun aGarbledRecordEndsTheLog() {
        session { m, _ ->
            m.learn(null, listOf(entry("你", "ni")))
            m.learn(null, listOf(entry("好", "hao")))
        }
        val bytes = file.readBytes()
        bytes[bytes.size - 6] = (bytes[bytes.size - 6] + 1).toByte()
        file.writeBytes(bytes)
        assertEquals(mapOf("你(ni)" to 1f), counts(session()))
    }

    @Test
    fun recordsOfOtherSyllablesOrTypesAreSkipped() {
        val m = model()
        val good = UserLog.sentence(null, listOf(entry("你", "ni")))
        // the same record with a syllable this build lacks, and one of a type from a later version
        val unknown = rewrite(UserLog.sentence(null, listOf(entry("好", "hao")))) { it.replace("hao", "hqo") }
        val later = fixCrc(UserLog.word(entry("吗", "ma"), 1f).also { it[4] = 9 })
        val shortened = rewrite(UserLog.word(entry("吗", "ma"), 1f)) { it }
        val log = UserLog.header() + unknown + later + good + fixCrc(shortened.copyOf(shortened.size - 5).let { cut ->
            // a record whose fields end early, its CRC right: a writer's bug, skipped
            ByteBuffer.wrap(cut).putInt(0, cut.size - 8).array()
        })
        assertEquals(log.size, UserLog.read(log, m))
        assertEquals(mapOf("你(ni)" to 1f), counts(m))
    }

    /** [record] with its UTF-8 fields changed by [change], the CRC made right again. */
    private fun rewrite(record: ByteArray, change: (String) -> String): ByteArray {
        val text = String(record, Charsets.ISO_8859_1)
        return fixCrc(change(text).toByteArray(Charsets.ISO_8859_1))
    }

    private fun fixCrc(record: ByteArray): ByteArray {
        val length = ByteBuffer.wrap(record).getInt(0)
        val crc = java.util.zip.CRC32().apply { update(record, 4, length) }.value.toInt()
        ByteBuffer.wrap(record).putInt(4 + length, crc)
        return record
    }

    @Test
    fun aFileOfAnotherFormatIsKeptAside() {
        file.writeText("not a log")
        val m = session { m, _ -> m.learn(null, listOf(entry("你", "ni"))) }
        assertEquals("not a log", File(file.path + UserStore.UNREADABLE).readText())
        assertEquals(counts(m), counts(session()))
        assertThrows(IllegalArgumentException::class.java) { UserLog.read("FXUL".toByteArray(), model()) }
        assertFalse(UserLog.hasHeader(ByteArray(3)))
    }

    @Test
    fun theLogIsCompactedToItsCounts() {
        val learned = session(compactAt = 200) { m, _ ->
            repeat(20) { m.learn(entry("你", "ni"), listOf(entry("拟好", "ni", "hao"), entry("吗", "ma"))) }
        }
        // twenty sentences of ~60 bytes, yet the log holds only the counts
        assertTrue(file.length().toString(), file.length() < 400)
        assertFalse(File(file.path + ".compacting").exists())
        val restored = session()
        assertEquals(counts(learned), counts(restored))
        val nihao = restored.id(entry("拟好", "ni", "hao"))
        assertEquals(learned.probability(NO_WORD, nihao), restored.probability(NO_WORD, nihao))
    }

    @Test
    fun countsOutgrowingTheLimitDoNotCompactOnEveryWrite() {
        session(compactAt = 10) { m, store ->
            m.learn(null, listOf(entry("你", "ni"), entry("好", "hao")))
            val compacted = file.readBytes()
            // the counts alone are past 10 bytes: the next write appends
            m.learn(null, listOf(entry("吗", "ma")))
            assertArrayEquals(compacted, file.readBytes().copyOf(compacted.size))
            assertTrue(file.length() > compacted.size)
            store.compact()
        }
        assertEquals(3, counts(session()).count { !it.key.contains(' ') })
    }

    @Test
    fun aStoreOpensOnceAndStopsListeningWhenClosed() {
        val m = model()
        val store = UserStore(file, m)
        assertThrows(IllegalStateException::class.java) { store.compact() }
        store.open()
        assertThrows(IllegalStateException::class.java) { store.open() }
        store.close()
        assertThrows(IllegalStateException::class.java) { store.open() }
        assertThrows(IllegalStateException::class.java) { store.compact() }
        m.learn(null, listOf(entry("你", "ni")))
        assertEquals(emptyMap<String, Float>(), counts(session()))
    }

    private val seed: (UserModel) -> Unit = { it.learn(null, listOf(entry("你", "ni"))); it.learn(null, listOf(entry("拟", "ni"))) }

    @Test
    fun aSeedIsWrittenOnceAsCountsAndLoggingGoesOnAfter() {
        val disk = FailingDisk(failing = 0)
        val m = model()
        UserStore(file, m, UserStore.DEFAULT_COMPACT_AT, {}, disk).use { store ->
            store.open(seed)
            // no sentence appended: the counts were written whole, in a file of their own
            assertEquals(0, disk.writes)
            m.learn(null, listOf(entry("你", "ni")))
            assertEquals(1, disk.writes)
        }
        assertEquals(mapOf("你(ni)" to 2f, "拟(ni)" to 1f), counts(session()))
    }

    @Test
    fun onlyALogWithNothingLearnedIsSeeded() {
        var seeded = 0
        val counting: (UserModel) -> Unit = { seeded++; seed(it) }
        // a header alone: killed before anything was learned
        file.writeBytes(UserLog.header())
        UserStore(file, model()).use { it.open(counting) }
        assertEquals(1, seeded)
        UserStore(file, model()).use { it.open(counting) }
        assertEquals(1, seeded)
        assertEquals(mapOf("你(ni)" to 1f, "拟(ni)" to 1f), counts(session()))
    }

    @Test
    fun aSeedThatCannotBeWrittenFailsTheOpenAndIsTriedAgain() {
        // where the counts would be written first
        val blocker = File(file.path + ".compacting").apply { mkdir() }
        assertThrows(IOException::class.java) { UserStore(file, model()).open(seed) }
        assertFalse(file.exists())
        blocker.delete()
        UserStore(file, model()).use { it.open(seed) }
        assertEquals(mapOf("你(ni)" to 1f, "拟(ni)" to 1f), counts(session()))
    }

    /** Writes through to [file] but, on write [failing], only half the bytes, then fails. */
    private class FailingDisk(private val failing: Int) : (File) -> OutputStream {
        var writes = 0

        override fun invoke(f: File): OutputStream = object : FilterOutputStream(FileOutputStream(f, true)) {
            override fun write(b: ByteArray) {
                if (++writes == failing) {
                    out.write(b, 0, b.size / 2)
                    throw IOException("disk full")
                }
                out.write(b)
            }
        }
    }

    @Test
    fun aFailedWriteLearnsAnywayAndIsCutFromTheFile() {
        val m = model()
        val errors = ArrayList<IOException>()
        UserStore(file, m, UserStore.DEFAULT_COMPACT_AT, { errors += it }, FailingDisk(failing = 2)).use {
            it.open()
            m.learn(null, listOf(entry("你", "ni")))
            // the pick goes through: learned in memory, the torn record cut off
            m.learn(null, listOf(entry("好", "hao")))
            m.learn(null, listOf(entry("吗", "ma")))
        }
        assertEquals(listOf("disk full"), errors.map { it.message })
        assertEquals(3f, m.total)
        // what followed the failure was not lost behind it
        assertEquals(mapOf("你(ni)" to 1f, "吗(ma)" to 1f), counts(session()))
    }

    @Test
    fun aFailedCompactionKeepsTheLogAndWaitsForItToDouble() {
        val errors = ArrayList<IOException>()
        // where the compacted log would go, something that cannot be written
        File(file.path + ".compacting").mkdirs()
        val m = model()
        UserStore(file, m, 60, { errors += it }).use {
            it.open()
            repeat(4) { m.learn(null, listOf(entry("你", "ni"))) }
        }
        // one attempt past 60 bytes, none again before 2 × the log
        assertEquals(1, errors.size)
        assertEquals(counts(m), counts(session()))
    }

    @Test
    fun aHeaderCutShortStartsAfresh() {
        file.writeBytes(UserLog.header().copyOf(3))
        val m = session { m, _ -> m.learn(null, listOf(entry("你", "ni"))) }
        assertFalse(File(file.path + UserStore.UNREADABLE).exists())
        assertEquals(counts(m), counts(session()))
        file.writeBytes(ByteArray(0))
        assertEquals(emptyMap<String, Float>(), counts(session()))
        assertFalse(File(file.path + UserStore.UNREADABLE).exists())
    }

    @Test
    fun aFileKeptAsideIsNotOverwritten() {
        File(file.path + UserStore.UNREADABLE).writeText("first")
        file.writeText("second")
        session()
        assertEquals("first", File(file.path + UserStore.UNREADABLE).readText())
        assertEquals("second", File(file.path + UserStore.UNREADABLE + ".1").readText())
    }

    @Test
    fun aSentenceTooLongForARecordIsNotWritten() {
        val long = List(UserLog.MAX_RECORD / 4) { entry("你", "ni") }
        assertTrue(UserLog.sentence(null, long).size > UserLog.MAX_RECORD)
        val m = session { m, _ ->
            m.learn(null, long)
            m.learn(null, listOf(entry("好", "hao")))
        }
        assertEquals(mapOf("好(hao)" to 1f), counts(session()))
        assertEquals(long.size + 1f, m.total)
    }
}
