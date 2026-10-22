/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.backup

import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.FakeClock
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.LearnRecords
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.corrected
import org.fcitx.fcitx5.android.engine.learn.row
import org.fcitx.fcitx5.android.engine.store.RecordFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class LearnBackupTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val head = BackupHead(1_792_461_600_000L, 3, 42L, BackupHead.SAVED, "0.3.1")

    private fun learned(): LearnState {
        val log = LearnLog(null, FakeClock()).apply { open() }
        log.record(listOf(corrected("ni", "拟", "ni")))
        log.record(listOf(row("hao", "好", "hao", prev = "拟")))
        log.pin(Code(Kinds.FULL, "nh"), "你好", "ni hao", on = true)
        log.state.apply(LearnRecord.NoPredictRow("你", "好"))
        return log.state
    }

    @Test
    fun aBackupReadsBackAsWritten() {
        val state = learned()
        val added = listOf(ListedWord("幽默", "you'mo"))
        val blocked = listOf(ListedWord("坏词", "huai'ci"))
        val read = LearnBackup.read(LearnBackup.bytes(head, state, added, blocked))
        assertEquals(head, read.head)
        assertEquals(state.dump(), read.state.dump())
        assertEquals(added, read.added)
        assertEquals(blocked, read.blocked)
        // 不再联想 rides in the snapshot
        assertTrue(("你" to "好") in read.state.noPredict)
    }

    @Test
    fun itsHeadReadsAloneForTheList() {
        val bytes = LearnBackup.bytes(head, learned(), emptyList(), emptyList())
        assertEquals(head, LearnBackup.head(ByteArrayInputStream(bytes)))
        // a log has no head
        assertNull(LearnBackup.head(ByteArrayInputStream(logBytes(learned()))))
    }

    // a zip of an earlier build has the log itself
    @Test
    fun aBareLogReadsAsABackupOfNoHead() {
        val state = learned()
        val read = LearnBackup.read(logBytes(state))
        assertNull(read.head)
        assertEquals(state.dump(), read.state.dump())
    }

    // WD-10d: a copy broken off part-way, at any byte, reads as nothing rather than a part
    @Test
    fun aBackupCutShortDoesNotRead() {
        val bytes = LearnBackup.bytes(head, learned(), listOf(ListedWord("幽默", "you'mo")), emptyList())
        // between two records too: the END is gone
        for (size in RecordFormat.HEADER_SIZE until bytes.size) {
            assertThrows(IOException::class.java) { LearnBackup.read(bytes.copyOf(size)) }
        }
        // WD-10d's: a 179-byte export cut to 100
        assertThrows(IOException::class.java) { LearnBackup.read(bytes.copyOf(bytes.size * 100 / 179)) }
    }

    @Test(expected = IOException::class)
    fun aFileOfAnotherKindDoesNotRead() {
        LearnBackup.read("# youmo-words 1\n你好 ni'hao\n".toByteArray())
    }

    // a later build's rows: kept, written again by whatever backs this one up
    @Test
    fun rowsOfALaterBuildAreKept() {
        val state = learned()
        state.apply(LearnRecord.Unknown(60, byteArrayOf(1, 2, 3)))
        val read = LearnBackup.read(LearnBackup.bytes(head, state, emptyList(), emptyList()))
        assertEquals(listOf(LearnRecord.Unknown(60, byteArrayOf(1, 2, 3))), read.state.kept)
    }

    // review-int-records M1: the offsets a restore stored come back with the backup, 退回恢复前's copy too
    @Test
    fun theOffsetsARestoreStoredReadBack() {
        val state = BackupMerge.merge(learned(), learned(), 7L, sameInstall = false, minute = 0)
        assertEquals(setOf(7L), BackupMerge.origins(state).keys)
        val read = LearnBackup.read(LearnBackup.bytes(head, state, emptyList(), emptyList()))
        assertEquals(BackupMerge.origins(state), BackupMerge.origins(read.state))
    }

    // verify-int-E: a later build's type of the file's own range is not the log's, and not kept
    @Test
    fun aLaterBuildsBackupTypeIsNotKept() {
        val state = learned()
        state.apply(LearnRecord.Unknown(BackupMerge.ORIGIN, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 7, 0, 0, 0, 0)))
        // 75: the first after END, left for the backup file's own
        state.apply(LearnRecord.Unknown(75, byteArrayOf(1)))
        val read = LearnBackup.read(LearnBackup.bytes(head, state, emptyList(), emptyList()))
        assertEquals(listOf(BackupMerge.ORIGIN), read.state.kept.map { (it as LearnRecord.Unknown).type })
    }

    // kept rows of the file's range never land in it: END must be the only 74, and last
    @Test
    fun aKeptBackupRowIsNotWritten() {
        val state = learned()
        val added = listOf(ListedWord("幽默", "you'mo"))
        val before = LearnBackup.read(LearnBackup.bytes(head, state, added, emptyList()))
        for (type in listOf<Byte>(70, 71, 72, 74)) state.apply(LearnRecord.Unknown(type, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        val bytes = LearnBackup.bytes(head, state, added, emptyList())
        val read = LearnBackup.read(bytes)
        assertEquals(head, read.head)
        assertEquals(before.added, read.added)
        assertEquals(before.blocked, read.blocked)
        assertEquals(before.state.dump(), read.state.dump())
        val types = ArrayList<Byte>().also { t -> LearnBackup.FORMAT.spans(bytes) { start, _ -> t += bytes[start + 4] } }
        assertEquals(1, types.count { it == LearnBackup.END })
        assertEquals(1, types.count { it == LearnBackup.HEAD })
        assertEquals(LearnBackup.END, types.last())
    }

    @Test
    fun aBackupEndsWithItsLength() {
        val bytes = LearnBackup.bytes(head, learned(), emptyList(), emptyList())
        val end = bytes.copyOfRange(bytes.size - LearnBackup.END_SIZE, bytes.size)
        assertEquals(listOf<Byte>(0, 0, 0, 5, LearnBackup.END), end.take(5))
        assertEquals(bytes.size, java.nio.ByteBuffer.wrap(end, 5, 4).int)
    }

    @Test(expected = IOException::class)
    fun aBackupOfNoHeadDoesNotRead() {
        val out = ByteArrayOutputStream()
        out.write(LearnBackup.FORMAT.header())
        LearnLog.writeSnapshot(learned(), 0, out::write)
        out.write(LearnBackup.FORMAT.record(LearnBackup.END) { writeInt(out.size() + LearnBackup.END_SIZE) })
        LearnBackup.read(out.toByteArray())
    }

    // the layout of the drafts before END (f9ce4e11): head, snapshot, lists, no end
    @Test(expected = IOException::class)
    fun aBackupMadeBeforeTheEndDoesNotRead() {
        val bytes = LearnBackup.bytes(head, learned(), listOf(ListedWord("幽默", "you'mo")), emptyList())
        LearnBackup.read(bytes.copyOf(bytes.size - LearnBackup.END_SIZE))
    }

    @Test(expected = IOException::class)
    fun aRecordAfterTheEndDoesNotRead() {
        val bytes = LearnBackup.bytes(head, learned(), emptyList(), emptyList())
        LearnBackup.read(bytes + LearnBackup.FORMAT.record(LearnBackup.ADDED) { writeUTF("幽默"); writeUTF("you'mo") })
    }

    @Test
    fun anEndOfAnotherLengthDoesNotRead() {
        val bytes = LearnBackup.bytes(head, learned(), emptyList(), emptyList())
        val body = bytes.copyOf(bytes.size - LearnBackup.END_SIZE)
        for (length in listOf(bytes.size - 1, bytes.size + 1, 0, -1)) {
            assertThrows(IOException::class.java) { LearnBackup.read(body + LearnBackup.FORMAT.record(LearnBackup.END) { writeInt(length) }) }
        }
        // an END whose length does not parse is skipped by the reader, and is no end
        assertThrows(IOException::class.java) { LearnBackup.read(body + LearnBackup.FORMAT.record(LearnBackup.END) { writeByte(1) }) }
    }

    // a file of no head is the install its bytes tell: the same file one, another file another
    @Test
    fun aBareLogIsAnInstallOfItsOwn() {
        val a = LearnBackup.read(logBytes(learned())).origin
        assertEquals(a, LearnBackup.read(logBytes(learned())).origin)
        assertTrue(a > 0)
        assertNotEquals(a, LearnBackup.read(logBytes(LearnState())).origin)
    }

    @Test
    fun aLogWrittenOpensAsTheState() {
        val state = learned()
        val file = File(folder.root, LearnLog.FILE)
        file.outputStream().use { LearnBackup.writeLog(it, state) }
        assertEquals(state.dump(), LearnBackup.replay(file.readBytes()).first.dump())
        val log = LearnLog(file, FakeClock()).apply { open() }
        assertEquals(state.dump(), log.state.dump())
        log.close()
    }

    private fun logBytes(state: LearnState): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(LearnRecords.FORMAT.header())
        LearnLog.writeSnapshot(state, state.cut, out::write)
        return out.toByteArray()
    }
}
