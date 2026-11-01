/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.backup

import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.FakeClock
import org.fcitx.fcitx5.android.engine.learn.ImportedWords
import org.fcitx.fcitx5.android.engine.learn.ImportedWords.Incoming
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.corrected
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.platform.write
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BackupControlTest {

    private val root = MemoryFiles()

    private val clock = FakeClock()
    private val log by lazy { LearnLog(root.file(LearnLog.FILE), clock).apply { open() } }
    private val added = LinkedHashSet<ListedWord>()
    private val blocked = LinkedHashSet<ListedWord>()
    private val imported = ArrayList<String>()

    // the app's address book, as AddressStore keeps it: a merge noted, the merge itself AddressBook's
    private var book = AddressData("", "")
    private val puts = ArrayList<Pair<AddressData, Boolean>>()

    @After
    fun close() = log.close()

    private var rewrites = true
    private var importFails = false
    private var importFailsAfterAdding = false
    private var importFailsAfterLearning = false

    private fun control(backups: Backups = Backups(root.dir(Backups.DIR), 1L), logBytes: (() -> ByteArray?)? = null) = BackupControl(
        backups, clock, "test",
        state = { log.state },
        lists = { added.toList() to blocked.toList() },
        rewrite = { t -> rewrites && log.rewrite(t) },
        addLists = { a, b ->
            added += a
            blocked += b
        },
        removeLists = { a, b ->
            added -= a.toSet()
            blocked -= b.toSet()
        },
        // as the user dictionary's import: each word line added
        importLines = { lines ->
            if (importFails) throw IOException("the import failed")
            for (line in lines) {
                imported += line
                line.split(' ').takeIf { it.size == 2 && !line.startsWith("#") }?.let { (text, code) -> added += ListedWord(text, code) }
            }
            // as WordImport.importWords' second write, its blocks, failing after the first
            if (importFailsAfterAdding) throw IOException("the blocks were not written")
            // as its learning lines, part written: the log changed, the lists not
            if (importFailsAfterLearning) {
                learn("拟")
                throw IOException("the learning was not all written")
            }
        },
        logBytes = logBytes,
        addresses = { book },
        putAddresses = { d, merge ->
            puts += d to merge
            book = if (merge) AddressData(lines(book.counts, d.counts), lines(book.addresses, d.addresses)) else d
        },
    )

    // a book's lines joined as a book: not a merge's counts, which BackupMerge's tests have
    private fun lines(a: String, b: String) = listOf(a, b).filter { it.isNotEmpty() }.joinToString("\n")

    private fun learn(text: String) = log.record(listOf(corrected("ni", text, "ni")))

    // what a restart would read: the log on disk replayed
    private fun onDisk() = LearnLog.replay(root.file(LearnLog.FILE).read()).dump().substringAfter('\n')

    private fun now() = log.state.dump().substringAfter('\n')

    private fun statLines() = log.state.dump().lines().filter { it.startsWith("stat ") }

    private fun backupOf(install: Long, vararg texts: String): ByteArray {
        val other = LearnLog(null, FakeClock()).apply { open() }
        for (t in texts) other.record(listOf(corrected("ni", t, "ni")))
        return LearnBackup.bytes(BackupHead(0, other.state.day, install, BackupHead.SAVED), other.state, emptyList(), emptyList())
    }

    // C25, review-DE-design DE-M1: invalidated, cleared, a fresh one made: nothing cleared comes back from this install's
    @Test
    fun aClearedLearningComesNotBackFromThisInstallsBackups() {
        val control = control()
        learn("拟")
        control.afterCommit()
        assertEquals(1, control.backups.list().size)
        // U-86's 全部清空, as stream E does it: invalidate, rewrite, a new one
        control.invalidate(LearningResult.ALL)
        assertTrue(control.backups.list().isEmpty())
        assertTrue(log.rewrite { _, out -> LearnBackup.writeLog(out, LearnState().apply { day = 1 }); true })
        control.saveIfOn()
        val kept = control.backups.list().single()
        assertTrue(kept.own)
        control.run(BackupOp.RestoreKept(kept.name))
        assertTrue(log.state.stats.isEmpty())
    }

    @Test
    fun aRestoreTakenBackPutsTheListsBackToo() {
        val control = control()
        learn("你")
        added += ListedWord("幽默", "you'mo")
        val bytes = LearnBackup.bytes(BackupHead(0, 0, 2L, BackupHead.SAVED), LearnState(), listOf(ListedWord("巴波", "ba'bo")), listOf(ListedWord("幽默", "you'mo")))
        val before = now()
        control.run(BackupOp.Restore(bytes))
        // blocked in the backup, added on the phone: added still
        assertEquals(setOf(ListedWord("幽默", "you'mo"), ListedWord("巴波", "ba'bo")), added)
        assertTrue(blocked.isEmpty())
        assertTrue(control.run(BackupOp.Revert).ok)
        assertEquals(setOf(ListedWord("幽默", "you'mo")), added)
        assertEquals(before, now())
        assertEquals(now(), onDisk())
        assertFalse(control.run(BackupOp.Revert).ok)
        // learning goes on after it
        learn("拟")
        assertEquals(2, log.state.stats.size)
    }

    // review-DE-design D-M1: the same backup restored again on a later typing day changes nothing
    @Test
    fun restoringTwiceDaysApartIsRestoringOnce() {
        val control = control()
        learn("你")
        val bytes = backupOf(2L, "拟", "拟", "泥")
        assertTrue(control.run(BackupOp.Restore(bytes)).ok)
        repeat(3) {
            clock.days(1)
            learn("你")
        }
        val once = now()
        assertTrue(control.run(BackupOp.Restore(bytes)).ok)
        assertEquals(once, now())
        assertEquals(once, onDisk())
    }

    // review-int-records M1: taken back, the phone keeps the offsets it had stored, so a backup restored again is not moved anew
    @Test
    fun aRevertKeepsTheOffsetsStored() {
        val control = control()
        learn("你")
        assertTrue(control.run(BackupOp.Restore(backupOf(2L, "拟"))).ok)
        val stored = BackupMerge.origins(log.state)
        assertEquals(setOf(2L), stored.keys)
        assertTrue(control.run(BackupOp.Restore(backupOf(3L, "泥"))).ok)
        assertTrue(control.run(BackupOp.Revert).ok)
        assertEquals(stored, BackupMerge.origins(log.state))
        assertEquals(stored, BackupMerge.origins(LearnLog.replay(root.file(LearnLog.FILE).read())))
    }

    // review-int-records S3: a backup's batch is numbered past one the phone undid (F2811: whose 撤销 the restore ends)
    @Test
    fun aBackupsBatchTakesNotTheIdOfOneUndone() {
        val control = control()
        learn("你")
        log.imports(ImportedWords.plan(1, "a.txt", 20000, listOf(Incoming("鲸园", "jing yuan"))) { _, _ -> false })
        log.imports(listOf(LearnRecord.ImportUndo(1)))
        val other = LearnLog(null, FakeClock()).apply { open() }
        other.record(listOf(corrected("ni", "拟", "ni")))
        other.imports(ImportedWords.plan(1, "b.txt", 20001, listOf(Incoming("幽默", "you mo"))) { _, _ -> false })
        val bytes = LearnBackup.bytes(BackupHead(0, other.state.day, 2L, BackupHead.SAVED), other.state, emptyList(), emptyList())
        assertTrue(control.run(BackupOp.Restore(bytes)).ok)
        assertEquals(listOf(2), log.state.imports.batches.map { it.id })
        // the restore is a rewrite: it ends the 撤销 of the batch undone before it (F2811)
        assertEquals(emptyList<LearnRecord>(), log.state.imports.restore(1))
    }

    // review-int-records S5: a log carried to another install (D2D) restores a backup made where it was on its own day line
    @Test
    fun aLogCarriedToAnotherInstallRestoresItsOldBackupsUnmoved() {
        val old = control()
        old.claim(log)
        repeat(5) {
            clock.days(1)
            learn("拟")
        }
        val backup = old.bytes(BackupHead.SAVED)
        repeat(10) {
            clock.days(1)
            learn("拟")
        }
        val phone = control(Backups(root.dir("carried"), 6L))
        phone.claim(log)
        val stats = statLines()
        assertTrue(phone.run(BackupOp.Restore(backup)).ok)
        // the same uses on the same days: none counted twice
        assertEquals(stats, statLines())
        assertEquals(mapOf(1L to 0, 6L to 0), BackupMerge.origins(log.state))
    }

    // D-S4: taken back, the typing day stays where the phone has it, so the backups made since are not in its future
    @Test
    fun aRevertKeepsTheTypingDay() {
        val control = control()
        learn("你")
        control.run(BackupOp.Restore(backupOf(2L, "拟")))
        clock.days(1)
        learn("你")
        val day = log.state.day
        assertTrue(control.run(BackupOp.Revert).ok)
        assertEquals(day, log.state.day)
    }

    // D-S6: the user dictionary's export is a restore too: imported, the phone's kept first
    @Test
    fun anExportIsRestoredByItsImportAndTakenBack() {
        val control = control()
        learn("你")
        val export = "# youmo-words 1\n幽默 you'mo\n".toByteArray()
        val peeked = control.run(BackupOp.Peek(export))
        assertTrue(peeked.ok)
        assertNull(peeked.head)
        assertTrue(control.run(BackupOp.Restore(export)).ok)
        assertEquals(listOf("# youmo-words 1", "幽默 you'mo", ""), imported)
        assertEquals(listOf(ListedWord("幽默", "you'mo")), added.toList())
        val learned = now()
        assertTrue(control.run(BackupOp.Kept).canRevert)
        // U-100: taken back, the lists and the learning as before
        assertTrue(control.run(BackupOp.Revert).ok)
        assertTrue(added.isEmpty())
        assertEquals(learned, now())
        assertFalse(control.run(BackupOp.Kept).canRevert)
    }

    // an export whose import fails leaves 退回恢复前 where it was, as a backup's restore does
    @Test
    fun aFailedExportRestoreKeepsTheCopyBefore() {
        val control = control()
        learn("你")
        control.run(BackupOp.Restore(backupOf(2L, "拟")))
        val copy = control.backups.beforeRestore()
        importFails = true
        assertFalse(control.run(BackupOp.Restore("# youmo-words 1\n幽默 you'mo\n".toByteArray())).ok)
        assertTrue(copy.contentEquals(control.backups.beforeRestore()))
    }

    // F3146: none before it, the copy the failed import made is dropped, not a 退回恢复前 that reverts nothing
    @Test
    fun aFailedExportRestoreWithNoCopyBeforeLeavesNone() {
        val control = control()
        learn("你")
        importFails = true
        assertFalse(control.run(BackupOp.Restore("# youmo-words 1\n幽默 you'mo\n".toByteArray())).ok)
        assertFalse(control.run(BackupOp.Kept).canRevert)
    }

    // F3139: an import that failed after adding its words keeps the copy made before it, so they can be taken back
    @Test
    fun anExportRestoreFailedPartWayCanBeTakenBack() {
        val control = control()
        learn("你")
        control.run(BackupOp.Restore(backupOf(2L, "拟")))
        val copy = control.backups.beforeRestore()
        importFailsAfterAdding = true
        assertFalse(control.run(BackupOp.Restore("# youmo-words 1\n幽默 you'mo\n".toByteArray())).ok)
        assertTrue(ListedWord("幽默", "you'mo") in added)
        assertFalse(copy.contentEquals(control.backups.beforeRestore()))
        assertTrue(control.run(BackupOp.Revert).ok)
        assertFalse(ListedWord("幽默", "you'mo") in added)
    }

    // F3202: an import that failed after learning, its lists unchanged, keeps the copy too
    @Test
    fun anExportRestoreFailedAfterLearningCanBeTakenBack() {
        val control = control()
        learn("你")
        importFailsAfterLearning = true
        assertFalse(control.run(BackupOp.Restore("# youmo-words 1\n".toByteArray())).ok)
        assertTrue(control.run(BackupOp.Kept).canRevert)
        assertTrue(control.run(BackupOp.Revert).ok)
        assertFalse("拟" in now())
    }

    // F3059, F3103: a backup's block of a word the phone added and pinned: added and pinned still once restored
    @Test
    fun aRestoredBlockOfAWordThePhoneAddedLeavesItsPin() {
        val control = control()
        val code = Code(Kinds.FULL, "ym")
        added += ListedWord("幽默", "you'mo")
        log.pin(code, "幽默", "you mo", on = true)
        val bytes = LearnBackup.bytes(BackupHead(0, 0, 2L, BackupHead.SAVED), LearnState(), emptyList(), listOf(ListedWord("幽默", "you'mo")))
        assertTrue(control.run(BackupOp.Restore(bytes)).ok)
        assertEquals("幽默", log.state.pins[code]?.text)
        assertTrue(ListedWord("幽默", "you'mo") in added)
        assertTrue(blocked.isEmpty())
    }

    // a restore that fails leaves 退回恢复前 where it was
    @Test
    fun aFailedRestoreKeepsTheCopyBefore() {
        val control = control()
        learn("你")
        control.run(BackupOp.Restore(backupOf(2L, "拟")))
        val copy = control.backups.beforeRestore()
        rewrites = false
        clock.minutes(5)
        assertFalse(control.run(BackupOp.Restore(backupOf(3L, "泥"))).ok)
        assertTrue(copy.contentEquals(control.backups.beforeRestore()))
        // none before: none after
        control.backups.dropBeforeRestore()
        assertFalse(control.run(BackupOp.Restore(backupOf(3L, "泥"))).ok)
        assertNull(control.backups.beforeRestore())
    }

    // a stash taken again: the copy an earlier try made is kept, unless the phone learned since (that try's rewrite failed)
    @Test
    fun aStashRetriedKeepsTheEarlierTrysCopyOnly() {
        val control = control()
        learn("你")
        val stashed = clock.now()
        clock.minutes(1)
        control.restore(LearnBackup.read(backupOf(2L, "拟")), since = stashed)
        val copy = checkNotNull(control.backups.beforeRestore())
        clock.minutes(1)
        control.restore(LearnBackup.read(backupOf(2L, "拟")), since = stashed)
        assertTrue(copy.contentEquals(control.backups.beforeRestore()))
        clock.minutes(10)
        learn("泥")
        control.restore(LearnBackup.read(backupOf(2L, "拟")), since = stashed)
        assertFalse(copy.contentEquals(control.backups.beforeRestore()))
    }

    @Test
    fun aFileOfNeitherIsSaidSo() {
        val control = control()
        assertFalse(control.run(BackupOp.Peek("not a backup".toByteArray())).ok)
        assertFalse(control.run(BackupOp.Restore("not a backup".toByteArray())).ok)
        assertFalse(control.run(BackupOp.Kept).canRevert)
    }

    // WD-10d: a backup cut short is not peeked as one, and its restore merges nothing
    @Test
    fun aBackupCutShortIsSaidSoAndChangesNothing() {
        val control = control()
        learn("你")
        val before = now()
        val cut = backupOf(2L, "拟").let { it.copyOf(it.size - 1) }
        assertFalse(control.run(BackupOp.Peek(cut)).ok)
        assertFalse(control.run(BackupOp.Restore(cut)).ok)
        assertEquals(before, now())
        assertFalse(control.run(BackupOp.Kept).canRevert)
    }

    // cut exactly where a record ends (the END gone): refused the same
    @Test
    fun aBackupCutAtItsEndIsSaidSoAndChangesNothing() {
        val control = control()
        learn("你")
        added += ListedWord("幽默", "you'mo")
        val before = now()
        val cut = backupOf(2L, "拟").let { it.copyOf(it.size - LearnBackup.END_SIZE) }
        assertFalse(control.run(BackupOp.Peek(cut)).ok)
        assertFalse(control.run(BackupOp.Restore(cut)).ok)
        assertEquals(before, now())
        assertEquals(setOf(ListedWord("幽默", "you'mo")), added)
        assertFalse(control.run(BackupOp.Kept).canRevert)
    }

    // 退回恢复前's copy cut at its end: the revert fails, nothing half put back
    @Test
    fun aCopyBeforeCutAtItsEndDoesNotRevert() {
        val backups = Backups(root.dir(Backups.DIR), 1L)
        val control = control(backups)
        learn("你")
        assertTrue(control.run(BackupOp.Restore(backupOf(2L, "拟"))).ok)
        val copy = checkNotNull(backups.beforeRestore())
        val dir = root.dir(Backups.DIR)
        val file = dir.files().map(dir::file).single { it.read().contentEquals(copy) }
        file.write(copy.copyOf(copy.size - LearnBackup.END_SIZE))
        val after = now()
        assertFalse(control.run(BackupOp.Revert).ok)
        assertEquals(after, now())
    }

    // every file this build makes ends in END and reads: no good file is refused
    @Test
    fun everyBackupMadeReads() {
        fun check(bytes: ByteArray) {
            val end = bytes.copyOfRange(bytes.size - LearnBackup.END_SIZE, bytes.size)
            assertEquals(listOf<Byte>(0, 0, 0, 5, LearnBackup.END), end.take(5))
            assertEquals(bytes.size, java.nio.ByteBuffer.wrap(end, 5, 4).int)
            LearnBackup.read(bytes)
        }
        learn("你")
        added += ListedWord("幽默", "you'mo")
        val copy = checkNotNull(control().run(BackupOp.Copy).bytes)
        check(copy)
        for (logBytes in listOf(null, { root.file(LearnLog.FILE).read() })) {
            val dir = root.dir("auto-${logBytes != null}")
            val control = control(Backups(dir, 1L), logBytes)
            control.afterCommit()
            check(checkNotNull(control.backups.read(control.backups.list().single().name)))
        }
        val control = control()
        assertTrue(control.run(BackupOp.Restore(backupOf(2L, "拟"))).ok)
        check(checkNotNull(control.backups.beforeRestore()))
        // the settings' zip carries Copy's bytes as they are
        val stash = MemoryFiles()
        stash.file(RestoreStash.BACKUP).write(copy)
        assertEquals(listOf(ListedWord("幽默", "you'mo")), RestoreStash.read(stash, {}).added)
    }

    // no directory to keep the phone's copy in: nothing restored, the learning as it was
    @Test
    fun noRestoreWithoutACopyBefore() {
        val control = control(Backups(null, 1L))
        learn("你")
        val before = now()
        assertFalse(control.run(BackupOp.Restore(backupOf(2L, "拟"))).ok)
        assertEquals(before, now())
    }

    @Test
    fun theAutomaticBackupsAreSwitchedOff() {
        val control = control()
        control.run(BackupOp.SetAuto(false))
        learn("拟")
        control.afterCommit()
        control.saveIfOn()
        assertTrue(control.backups.list().isEmpty())
    }

    // F2991, F3091: a clock set back after a commit times the backup no earlier than that commit's minute, so a clear from it drops it
    @Test
    fun aBackupAfterTheClockSetBackIsTimedAtItsLastCommit() {
        val control = control()
        learn("拟")
        val minute = log.state.lastMinute
        clock.minutes(-60)
        val head = checkNotNull(LearnBackup.head(checkNotNull(control.run(BackupOp.Copy).bytes).inputStream()))
        assertTrue(head.time >= minute * LearnClock.MINUTE)
        control.saveIfOn()
        assertEquals(1, control.backups.list().size)
        control.invalidate(minute)
        assertTrue(control.backups.list().isEmpty())
    }

    // DE-S2: made of the log as on disk, off the keyboard's thread
    @Test
    fun anAutomaticBackupIsMadeOfTheLogOnDisk() {
        val control = control(logBytes = { root.file(LearnLog.FILE).read() })
        learn("拟")
        control.afterCommit()
        val kept = control.backups.list().single()
        val state = LearnBackup.read(checkNotNull(control.backups.read(kept.name))).state
        assertEquals(now(), state.dump().substringAfter('\n'))
    }

    // an install of no id (its file unwritable) makes none, and takes no backup for its own
    @Test
    fun anInstallOfNoIdMakesNone() {
        val control = control(Backups(root.dir(Backups.DIR), 0L))
        learn("拟")
        control.afterCommit()
        control.saveIfOn()
        assertTrue(control.backups.list().isEmpty())
        assertFalse(control.run(BackupOp.Peek(backupOf(0L))).own)
    }

    @Test
    fun theAddressBarsBookGoesWithEachBackupAndComesBackMerged() {
        val control = control()
        book = AddressData("1\t@qq.com", "1\ta@qq.com")
        learn("拟")
        control.afterCommit()
        val auto = control.backups.list().single()
        assertEquals(book, LearnBackup.read(control.backups.read(auto.name)!!).addresses)
        assertEquals(book, LearnBackup.read(control.bytes(BackupHead.SAVED)).addresses)
        // another's restored: merged in; 退回恢复前: the phone's as it was, whole
        val other = LearnBackup.bytes(BackupHead(0, 0, 2L, BackupHead.SAVED), LearnState(), emptyList(), emptyList(), AddressData("2\t@163.com", "1\tb@163.com"))
        control.run(BackupOp.Restore(other))
        assertEquals(listOf(AddressData("2\t@163.com", "1\tb@163.com") to true), puts)
        assertTrue(control.run(BackupOp.Revert).ok)
        assertEquals(AddressData("1\t@qq.com", "1\ta@qq.com") to false, puts.last())
        assertEquals(AddressData("1\t@qq.com", "1\ta@qq.com"), book)
    }

    @Test
    fun aBackupMadeBeforeAddressesWentWithItLeavesThemAsTheyAre() {
        val control = control()
        book = AddressData("1\t@qq.com", "1\ta@qq.com")
        // v0.4.0's: none in it
        control.run(BackupOp.Restore(backupOf(2L, "你")))
        assertEquals(emptyList<Pair<AddressData, Boolean>>(), puts)
        // a copy before made by v0.4.0, taken back: as they are
        control.backups.saveBeforeRestore(backupOf(1L, "拟"))
        assertTrue(control.run(BackupOp.Revert).ok)
        assertEquals(emptyList<Pair<AddressData, Boolean>>(), puts)
        assertEquals(AddressData("1\t@qq.com", "1\ta@qq.com"), book)
    }
}
