/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.BackupResult
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.platform.LogFile
import org.fcitx.fcitx5.android.engine.platform.LogOutput
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles
import org.fcitx.fcitx5.android.engine.session.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * What the engines do when the disk refuses the learning record a write (review R11 F2743), and
 * that what is learned in memory alone is told, whatever the cause (061-userdict-loss).
 */
class EnginesRecordFaultTest : EnginesFixture() {

    @get:Rule
    val folder = TemporaryFolder()

    // review R11 F2743: an append the disk refuses is reported once; the act is done, and kept in memory for the run
    @Test
    fun anAppendRefusedIsReportedOnceAndTheActKeptInMemory() {
        val dir = folder.newFolder("engine")
        val errors = ArrayList<IOException>()
        val disk = RefusingDisk()
        Engines(::load, JvmFiles(dir), onError = { errors += it }, openAppend = disk::open).use { engines ->
            // 拟 used once, written: a word learned there, to delete
            engines.type(InputMethodCatalog.PINYIN, "ni")
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 1)
            assertEquals(listOf("你", "拟"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
            assertTrue(Offer.FORGET in engines.offers(InputMethodCatalog.PINYIN, 1))
            disk.refuseNextWrite()
            assertTrue(engines.learning(InputMethodCatalog.PINYIN, LearningOp.Delete(1, "拟")) is LearningResult.Done)
            assertEquals(1, errors.size)
            // forgotten for the run all the same
            assertFalse(Offer.FORGET in engines.offers(InputMethodCatalog.PINYIN, 1))
            disk.refuseNextWrite()
            assertTrue(engines.learning(InputMethodCatalog.PINYIN, LearningOp.Pin(1, "拟")) is LearningResult.Done)
            assertEquals(2, errors.size)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            assertEquals(listOf("拟", "你"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
        }
    }

    /**
     * 0.5.2's fresh install had no user directory: the record did not open (ENOENT) and what was learned
     * lived in memory for days, gone with the process, nothing said. Learned in memory alone, for any
     * cause, it is told, once, as the first word learned is not kept; nothing on a record that keeps it.
     */
    @Test
    fun whatIsLearnedButNotKeptIsToldOnceWhateverTheCause() = assertRows(
        listOf<Pair<String, () -> UserFiles?>>(
            "no user directory (before the unlock)" to { null },
            "a user directory not there (0.5.2's fresh install)" to { JvmFiles(File(folder.root, "engine")) },
            "a record that does not open" to { JvmFiles(folder.newFolder()).also { it.dir(LearnLog.FILE).mkdirs() } },
            "a write refused" to { JvmFiles(folder.newFolder()) },
        ),
        { it.first },
    ) { (name, dir) ->
        var told = 0
        val disk = RefusingDisk()
        Engines(::load, dir(), unkept = { told++ }, openAppend = disk::open).use { engines ->
            if (name == "a write refused") {
                pick(engines)
                assertEquals("kept so far", 0, told)
                disk.refuseNextWrite()
            }
            pick(engines)
            assertEquals(1, told)
            // told once: the next words learned in memory too, said no more
            pick(engines)
            engines.learning(InputMethodCatalog.PINYIN, LearningOp.Delete(1, "拟"))
            assertEquals(1, told)
        }
    }

    @Test
    fun whatIsLearnedAndKeptIsNotTold() {
        var told = 0
        Engines(::load, JvmFiles(folder.newFolder()), unkept = { told++ }).use { engines ->
            repeat(2) { pick(engines) }
            engines.learning(InputMethodCatalog.PINYIN, LearningOp.Delete(1, "拟"))
        }
        assertEquals(0, told)
    }

    // 拟 picked under ni: a commit learned
    private fun pick(engines: Engines) {
        val at = engines.type(InputMethodCatalog.PINYIN, "ni").candidates.indexOf("拟")
        assertEquals("拟", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, at).commit)
    }

    // R25 decode-1 rest: a write refused and the record not opened again: learning stops saving, which the
    // overview says (the panels' "not saving" line); saving before
    @Uat("WD-46")
    @Test
    fun aRecordThatStoppedTakingWritesIsNotSaving() {
        val errors = ArrayList<IOException>()
        val disk = RefusingDisk()
        Engines(::load, JvmFiles(folder.newFolder()), onError = { errors += it }, openAppend = disk::open).use { engines ->
            pick(engines)
            assertTrue(overview(engines).saving)
            disk.refuseOpens = true
            disk.refuseNextWrite()
            pick(engines)
            assertEquals(2, errors.size)
            assertFalse(overview(engines).saving)
            // and takes no rewrite till a new start, which the app tells from a full disk by (R25 F8037)
            assertEquals(LearningResult.Saving(saving = false, rewrites = false), engines.learning(InputMethodCatalog.PINYIN, LearningOp.Saving))
        }
    }

    // R25 F8050: storage full, a forget's own write is refused: forgotten in memory alone, with nothing in the file
    // to take out. Its undo takes it back in memory and says done, as any undo; it said 「已经撤销不了了」, the
    // word still deleted
    @Uat("WD-46")
    @Test
    fun aForgetTheDiskRefusedIsTakenBackInMemory() {
        val errors = ArrayList<IOException>()
        val disk = RefusingDisk()
        Engines(::load, JvmFiles(folder.newFolder()), onError = { errors += it }, openAppend = disk::open).use { engines ->
            // 拟 used once, written: a word learned there, to delete
            pick(engines)
            assertEquals(listOf("你", "拟"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
            disk.full = true
            val done = engines.learning(InputMethodCatalog.PINYIN, LearningOp.Delete(1, "拟")) as LearningResult.Done
            assertTrue(done.undo != LearningResult.NO_UNDO)
            assertFalse(Offer.FORGET in engines.offers(InputMethodCatalog.PINYIN, 1))
            val said = errors.size
            assertEquals(LearningResult.Done(), engines.learning(InputMethodCatalog.PINYIN, LearningOp.Undo(done.undo)))
            assertEquals("nothing rewritten, nothing more said of the disk", said, errors.size)
            // learned again, as before the forget
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            assertEquals(listOf("你", "拟"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
            assertTrue(Offer.FORGET in engines.offers(InputMethodCatalog.PINYIN, 1))
            // taken back once
            assertEquals(LearningResult.Unavailable, engines.learning(InputMethodCatalog.PINYIN, LearningOp.Undo(done.undo)))
        }
    }

    // R25 F8079: a delete whose pressed candidate is refused after the one shown alike was forgotten takes that back
    // at once (LearningOps.acted). Storage full, the forget is in memory alone and is taken back there only where
    // nothing else is unwritten (R25 F8066); with a word learned in memory since the disk filled, the one alike
    // stays deleted, not done said all the same, rather than what was learned since dropped to bring it back
    @Uat("WD-46")
    @Test
    fun aDeleteRefusedAfterTheOneAlikeTakesItBackOnlyWhereNothingElseIsUnwritten() = assertRows(
        listOf(
            Triple("nothing else unwritten: the one alike taken back, both words there", false, setOf("你好", "拟好")),
            Triple("a word learned in memory since the disk filled: the one alike stays deleted, the word learned kept", true, setOf("你好")),
        ),
        { it.first },
    ) { (why, learnedSince, left) ->
        val disk = RefusingDisk()
        // 拟好 shown as 你好 is (as a conversion shows 国 as 國): deleted with it
        val alike = { text: String -> text.replace('拟', '你') }
        Engines(::load, JvmFiles(folder.newFolder()), openAppend = disk::open).use { engines ->
            assertTrue(engines.addWord("你好", "ni hao"))
            assertTrue(engines.addWord("拟好", "ni hao"))
            // the record in use, what its first use writes written: the forget is then all the disk refuses
            engines.type(InputMethodCatalog.PINYIN, "ni")
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            disk.full = true
            if (learnedSince) pick(engines)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            val list = engines.type(InputMethodCatalog.PINYIN, "nihao").candidates
            val at = list.indexOf("你好")
            assertTrue("$why: $list", at >= 0 && "拟好" in list)
            val acts = ArrayList<String>()
            val done = engines.learning(InputMethodCatalog.PINYIN, LearningOp.Delete(at, "你好"), alike) { event, i, text ->
                acts += text
                i != at && engines.pressShown(InputMethodCatalog.PINYIN, event, i)
            }
            assertEquals(why, LearningResult.Unavailable, done)
            assertTrue("$why: $acts", "拟好" in acts)
            assertEquals(why, left, engines.userWords().map { it.text }.toSet())
            if (learnedSince) {
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
                assertEquals(why, listOf("你", "拟"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
                assertTrue("$why: 拟 still learned", Offer.FORGET in engines.offers(InputMethodCatalog.PINYIN, 1))
            }
        }
    }

    // R25 F8038: a forget on disk is taken back by the record rewritten without it; a rewrite that fails is told as
    // failed (the chip then says 「没撤销成」, its own text). It was told as an act no longer the last,
    // 「已经撤销不了了」. Here the rewrite's file cannot be made, the record still open (as with no room for it)
    @Uat("WD-46")
    @Test
    fun aForgetOnDiskWhoseRewriteFailsSaysItFailed() {
        val errors = ArrayList<IOException>()
        val dir = folder.newFolder()
        Engines(::load, JvmFiles(dir), onError = { errors += it }).use { engines ->
            // 拟 used once, written: a word learned there, to delete
            pick(engines)
            assertEquals(listOf("你", "拟"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
            val done = engines.learning(InputMethodCatalog.PINYIN, LearningOp.Delete(1, "拟")) as LearningResult.Done
            assertTrue(done.undo != LearningResult.NO_UNDO)
            assertFalse(Offer.FORGET in engines.offers(InputMethodCatalog.PINYIN, 1))
            // where a rewrite is written before it is renamed over the record (RecordStore's ".compacting"): a
            // directory there, not empty, is neither written as a file nor deleted
            File(File(dir, LearnLog.FILE + ".compacting"), "in the way").apply { parentFile.mkdirs() }.writeText("")
            val file = File(dir, LearnLog.FILE).readBytes()
            val said = errors.size
            assertEquals(LearningResult.Failed, engines.learning(InputMethodCatalog.PINYIN, LearningOp.Undo(done.undo)))
            assertEquals("said once", said + 1, errors.size)
            assertFalse("still forgotten", Offer.FORGET in engines.offers(InputMethodCatalog.PINYIN, 1))
            assertTrue(file.contentEquals(File(dir, LearnLog.FILE).readBytes()))
            // the record itself as it was: saving, and taking a rewrite
            assertEquals(LearningResult.Saving(saving = true, rewrites = true), engines.learning(InputMethodCatalog.PINYIN, LearningOp.Saving))
            // no longer the last act: that, not a failure, from then on
            assertEquals(LearningResult.Unavailable, engines.learning(InputMethodCatalog.PINYIN, LearningOp.Undo(done.undo)))
            assertEquals(said + 1, errors.size)
        }
    }

    // R25 F8018: such a record's file still holds what was typed before the write refused, and is not rewritten:
    // a clear says it failed. It said cleared (清掉最近) or unavailable (清空学到的), the file as it was either way.
    // R25 F8021: and nothing else is cleared: a clear deletes the automatic backups that hold what it clears and
    // the copy for 退回恢复前 before its rewrite, and did so before this one sure to fail, at every try
    @Uat("WD-46")
    @Test
    fun aClearOfARecordThatStoppedTakingWritesSaysItFailed() = assertRows(
        listOf(LearningOp.ClearRecent(LearningOp.Span.TEN_MINUTES), LearningOp.ClearLearned(all = false)),
        { "$it: failed, not cleared" },
    ) { clear ->
        val disk = RefusingDisk()
        val dir = folder.newFolder()
        // an install with an id, its backups written as they come due (on the first typing day), not on a thread
        Engines(::load, JvmFiles(dir), installFile = MemoryFiles().file("learn.install"), background = { it.run() }, openAppend = disk::open).use { engines ->
            pick(engines)
            // the phone's own copy restored: a copy for 退回恢复前
            val copy = checkNotNull(backups(engines, BackupOp.Copy).bytes)
            assertTrue("$clear", backups(engines, BackupOp.Restore(copy)).ok)
            val kept = backups(engines, BackupOp.Kept)
            assertTrue("$clear: a copy before the restore", kept.canRevert)
            val automatic = kept.backups.filter { it.own }.map { it.name }
            assertEquals("$clear: an automatic backup of the first typing day", 1, automatic.size)
            disk.refuseOpens = true
            disk.refuseNextWrite()
            pick(engines)
            val file = File(dir, LearnLog.FILE).readBytes()
            assertEquals(LearningResult.Failed, engines.learning(InputMethodCatalog.PINYIN, clear))
            assertTrue(file.contentEquals(File(dir, LearnLog.FILE).readBytes()))
            val left = backups(engines, BackupOp.Kept)
            assertTrue("$clear: the copy before the restore still there", left.canRevert)
            assertTrue("$clear: the automatic backup still there", left.backups.map { it.name }.containsAll(automatic))
        }
    }

    private fun backups(engines: Engines, op: BackupOp) = engines.learning(null, op) as BackupResult

    // R25 F7819: storage full, as it happens: the record opens again after the write refused and the next
    // writes are refused too. Not saving for as long as that lasts, and saving again, with no restart, once
    // a word is written
    @Uat("WD-46")
    @Test
    fun aRecordWhoseWritesKeepFailingIsNotSavingUntilOneIsWritten() {
        val errors = ArrayList<IOException>()
        val disk = RefusingDisk()
        Engines(::load, JvmFiles(folder.newFolder()), onError = { errors += it }, openAppend = disk::open).use { engines ->
            pick(engines)
            assertTrue(overview(engines).saving)
            assertEquals(LearningResult.Saving(true), engines.learning(InputMethodCatalog.PINYIN, LearningOp.Saving))
            disk.full = true
            pick(engines)
            // the panel's question alone says the same (R25 F7897)
            // not saving, but a rewrite still goes through once there is room, no restart needed (R25 F8037)
            assertEquals(LearningResult.Saving(saving = false, rewrites = true), engines.learning(InputMethodCatalog.PINYIN, LearningOp.Saving))
            // the write alone refused: the file was cut back and opened again
            assertEquals(listOf("full"), errors.map { it.message }.distinct())
            assertFalse(overview(engines).saving)
            pick(engines)
            assertFalse(overview(engines).saving)
            disk.full = false
            pick(engines)
            assertTrue(overview(engines).saving)
        }
    }

    // R25 F7953: a clear rewrote the record whole after the write refused: saving again from then, not only
    // from the next word learned (none is, with habits off or in a field that learns nothing). R25 F7964: a
    // 清掉最近 rewrites it only where the file holds records of that time; one that finds none there (what
    // was typed then was refused, in memory alone) clears that from memory and leaves the file, so it is
    // not saving till a write next goes through
    @Uat("WD-46")
    @Test
    fun aRecordAWriteWasRefusedIsSavingAgainOnceAClearRewroteIt() = assertRows(
        listOf<FaultRow>(
            FaultRow("清空学到的: the record written whole", writtenFirst = true, LearningOp.ClearLearned(all = false), saving = true),
            FaultRow(
                "清掉最近, a word of that time in the file: taken out, the record written whole",
                writtenFirst = true,
                LearningOp.ClearRecent(LearningOp.Span.TEN_MINUTES),
                saving = true,
            ),
            FaultRow(
                "清掉最近, nothing of that time in the file: no rewrite, nothing told of the disk",
                writtenFirst = false,
                LearningOp.ClearRecent(LearningOp.Span.TEN_MINUTES),
                saving = false,
            ),
        ),
        { it.why },
    ) { row ->
        val disk = RefusingDisk()
        Engines(::load, JvmFiles(folder.newFolder()), openAppend = disk::open).use { engines ->
            if (row.writtenFirst) pick(engines)
            disk.full = true
            pick(engines)
            assertFalse(row.why, overview(engines).saving)
            disk.full = false
            assertTrue(row.why, engines.learning(InputMethodCatalog.PINYIN, row.clear) is LearningResult.Cleared)
            assertEquals(row.why, row.saving, overview(engines).saving)
        }
    }

    private class FaultRow(val why: String, val writtenFirst: Boolean, val clear: LearningOp, val saving: Boolean)

    private fun overview(engines: Engines) =
        engines.learning(InputMethodCatalog.PINYIN, LearningOp.Overview) as LearningResult.Overview
}

/**
 * The learning record's disk in a test: the next write after [refuseNextWrite] throws, as a full disk would,
 * and the store recovers as from one (opens the file again); with [refuseOpens], that open throws too.
 * While [full], every write throws and the file opens, as on a disk with no room left. The record's file
 * handed over as [file] has its rewrites refused then too, and does not read while [unreadable].
 */
internal class RefusingDisk {
    private var refuseNext = false
    var refuseOpens = false
    var full = false
    var unreadable = false

    fun refuseNextWrite() {
        refuseNext = true
    }

    fun open(file: LogFile): OutputStream {
        if (refuseOpens) throw IOException("refused")
        val real = file.append()
        return object : OutputStream() {
            override fun write(b: Int) {
                refused()
                real.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                refused()
                real.write(b, off, len)
            }

            override fun flush() = real.flush()
            override fun close() = real.close()
        }
    }

    /**
     * [real] on this disk: while [full], a file made anew is refused too, as its appends are, so a rewrite of it
     * (written beside it, then renamed over it) throws, the file as it was.
     */
    fun file(real: LogFile): LogFile = OnDisk(real)

    private inner class OnDisk(val real: LogFile) : LogFile by real {
        override fun create(): LogOutput = if (full) throw IOException("full") else real.create()

        override fun read(): ByteArray = if (unreadable) throw IOException("unreadable") else real.read()

        override fun beside(suffix: String): LogFile = OnDisk(real.beside(suffix))

        // the files themselves, which a rename asks for
        override fun renameTo(dest: LogFile) = real.renameTo(if (dest is OnDisk) dest.real else dest)
    }

    private fun refused() {
        if (full) throw IOException("full")
        if (refuseNext) {
            refuseNext = false
            throw IOException("refused")
        }
    }
}
