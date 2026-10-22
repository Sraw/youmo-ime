/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.backup

import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class BackupsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun bytes(day: Int, install: Long, kind: Int = BackupHead.AUTO, time: Long = day * 1000L) =
        LearnBackup.bytes(BackupHead(time, day, install, kind), LearnState().apply { this.day = day }, emptyList(), emptyList())

    private fun Backups.saveOn(day: Int, time: Long = day * 1000L) = saveAuto(day) { bytes(day, installId, time = time) }

    // U-106: on the first typing day, then every seven
    @Test
    fun oneIsDueOnTheFirstTypingDayThenEverySeven() {
        val backups = Backups(folder.root, 1L)
        assertFalse(backups.due(0))
        assertTrue(backups.due(1))
        backups.saveOn(1)
        assertFalse(backups.due(7))
        assertTrue(backups.due(8))
        // a log behind the newest (begun again, its old one unreadable): due at once
        backups.saveOn(8)
        assertTrue(backups.due(3))
        backups.auto = false
        assertFalse(backups.due(8))
    }

    @Test
    fun theTwoNewestOfThisInstallAreKeptAndOthersLeft() {
        val backups = Backups(folder.root, 1L)
        File(folder.root, "copied.fxlb").writeBytes(bytes(3, 2L))
        for (day in listOf(1, 8, 15)) backups.saveOn(day)
        val listed = backups.list()
        assertEquals(listOf(15, 8, 3), listed.map { it.head.day })
        assertEquals(listOf(true, true, false), listed.map { it.own })
        // read again from the files, as a new start does
        assertFalse(Backups(folder.root, 1L).due(21))
        assertTrue(Backups(folder.root, 1L).due(22))
    }

    // F3090: a log begun again behind the newest (due's day < last): the one written for it is kept, the newest other with it
    @Test
    fun oneWrittenForALogBehindTheNewestIsKept() {
        val backups = Backups(folder.root, 1L)
        for (day in listOf(8, 15)) backups.saveOn(day)
        assertTrue(backups.due(3))
        backups.saveOn(3, time = 99_000L)
        assertEquals(listOf(3, 15), backups.list().map { it.head.day }.sorted())
        // F3238: the next start reads the one for the log as it is now, not the newest: not due again till its seven days
        assertFalse(Backups(folder.root, 1L).due(4))
        assertTrue(Backups(folder.root, 1L).due(10))
    }

    // C25: what was cleared never comes back from this install's backups; another's are left
    @Test
    fun aClearDropsThisInstallsBackupsAndTheCopyBeforeARestore() {
        val backups = Backups(folder.root, 1L)
        backups.saveOn(1)
        backups.saveBeforeRestore(bytes(1, 1L, BackupHead.BEFORE_RESTORE))
        File(folder.root, "copied.fxlb").writeBytes(bytes(3, 2L))
        assertTrue(backups.hasBeforeRestore())
        backups.invalidate(Int.MIN_VALUE)
        assertEquals(listOf("copied.fxlb"), backups.list().map { it.name })
        assertNull(backups.beforeRestore())
        assertFalse(backups.hasBeforeRestore())
        assertTrue(backups.due(2))
    }

    // DE-S1: U-88 drops only those made at or after its cut, an older one holding nothing of the span
    @Test
    fun aClearOfTheLastMinutesDropsOnlyThoseMadeSince() {
        val backups = Backups(folder.root, 1L)
        backups.saveOn(1, time = 10 * MINUTE)
        backups.saveOn(8, time = 20 * MINUTE)
        backups.invalidate(15)
        assertEquals(listOf(10 * MINUTE), backups.list().map { it.head.time })
    }

    // DE-M1 step 1: one being written in the background is waited for, then dropped with the rest
    @Test
    fun aClearWaitsForOneBeingWritten() {
        val started = CountDownLatch(1)
        val made = AtomicBoolean()
        val backups = Backups(folder.root, 1L, background = { r -> Thread { started.countDown(); Thread.sleep(200); r.run() }.start() })
        backups.saveAuto(1) { made.set(true); bytes(1, 1L) }
        started.await()
        backups.invalidate(Int.MIN_VALUE)
        // returned only once the write was done: one landing after would bring back what is cleared
        assertTrue(made.get())
        assertTrue(backups.list().isEmpty())
    }

    // one not written is due again, the failure told: never seven typing days with none, unseen
    @Test
    fun oneNotWrittenIsDueAgain() {
        val errors = ArrayList<IOException>()
        val backups = Backups(folder.root, 1L, onError = { errors += it })
        backups.saveAuto(1) { throw IOException("disk full") }
        assertTrue(backups.due(1))
        backups.saveAuto(1) { throw IllegalStateException("the log read wrong") }
        assertTrue(backups.due(1))
        assertEquals(2, errors.size)
        assertTrue(errors[1].cause is IllegalStateException)
        // nothing to back up yet (no log read): due again too, nothing told
        backups.saveAuto(1) { null }
        assertTrue(backups.due(1))
        assertEquals(2, errors.size)
    }

    // the two kept are the newest by typing day, as due reads them: a clock set back keeps the one just written
    @Test
    fun theNewestByTypingDayAreKeptAfterTheClockWasSetBack() {
        val backups = Backups(folder.root, 1L)
        backups.saveOn(1, time = 5000)
        backups.saveOn(8, time = 3000)
        backups.saveOn(15, time = 1000)
        assertEquals(setOf(15, 8), backups.list().map { it.head.day }.toSet())
        assertFalse(backups.due(21))
    }

    @Test
    fun theCopyBeforeARestoreIsNoListedName() {
        val backups = Backups(folder.root, 1L)
        backups.saveBeforeRestore(bytes(1, 1L, BackupHead.BEFORE_RESTORE))
        assertNull(backups.read(Backups.BEFORE_RESTORE))
        assertFalse(backups.delete(Backups.BEFORE_RESTORE))
        assertTrue(backups.hasBeforeRestore())
    }

    @Test
    fun aNameIsNoPathOutOfTheDirectory() {
        val outside = folder.newFile("outside.fxlb")
        val backups = Backups(folder.newFolder("backups"), 1L)
        assertFalse(backups.delete("../outside.fxlb"))
        assertTrue(outside.exists())
        assertNull(backups.read("../outside.fxlb"))
    }

    @Test
    fun aFileThatIsNoBackupIsNotListed() {
        File(folder.root, "broken.fxlb").writeBytes(byteArrayOf(1, 2))
        var errors = 0
        assertEquals(emptyList<BackupInfo>(), Backups(folder.root, 1L, onError = { errors++ }).list())
        assertEquals(1, errors)
    }

    @Test
    fun anInstallKeepsItsIdAndAnotherHasAnother() {
        val file = File(folder.root, "learn.install")
        val id = Backups.installId(file)
        assertEquals(id, Backups.installId(file))
        assertNotEquals(id, Backups.installId(File(folder.root, "other")))
        assertNotEquals(0L, Backups.installId(null))
    }

    // a garbled id is replaced, told: this install's earlier backups are another's from then on
    @Test
    fun aGarbledIdIsReplacedAndTold() {
        val file = File(folder.root, "learn.install").apply { writeText("not a number") }
        var errors = 0
        val id = Backups.installId(file) { errors++ }
        assertNotEquals(0L, id)
        assertEquals(1, errors)
        assertEquals(id, Backups.installId(file) { errors++ })
        assertEquals(1, errors)
    }

    // D-N1: an id that cannot be kept is none, told: never a new one each start
    @Test
    fun anIdThatCannotBeKeptIsNone() {
        val blocker = folder.newFile("a-file")
        var errors = 0
        assertEquals(0L, Backups.installId(File(blocker, "learn.install")) { errors++ })
        assertEquals(1, errors)
        assertFalse(Backups(folder.root, 0L).due(1))
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
