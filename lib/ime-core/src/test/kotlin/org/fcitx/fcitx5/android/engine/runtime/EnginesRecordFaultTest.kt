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

/** What the engines do when the disk refuses the learning record a write. */
class EnginesRecordFaultTest : EnginesFixture() {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun anAppendRefusedIsReportedOnceAndTheActKeptInMemory() {
        val dir = folder.newFolder("engine")
        val errors = ArrayList<IOException>()
        val disk = RefusingDisk()
        Engines(::load, JvmFiles(dir), onError = { errors += it }, openAppend = disk::open).use { engines ->
            engines.type(InputMethodCatalog.PINYIN, "ni")
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 1)
            assertEquals(listOf("你", "拟"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
            assertTrue(Offer.FORGET in engines.offers(InputMethodCatalog.PINYIN, 1))
            disk.refuseNextWrite()
            assertTrue(engines.learning(InputMethodCatalog.PINYIN, LearningOp.Delete(1, "拟")) is LearningResult.Done)
            assertEquals(1, errors.size)
            assertFalse(Offer.FORGET in engines.offers(InputMethodCatalog.PINYIN, 1))
            disk.refuseNextWrite()
            assertTrue(engines.learning(InputMethodCatalog.PINYIN, LearningOp.Pin(1, "拟")) is LearningResult.Done)
            assertEquals(2, errors.size)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            assertEquals(listOf("拟", "你"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
        }
    }

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

    // the panels' "not saving" line
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
            // and takes no rewrite till a new start, which the app tells from a full disk by
            assertEquals(LearningResult.Saving(saving = false, rewrites = false), engines.learning(InputMethodCatalog.PINYIN, LearningOp.Saving))
        }
    }

    // its undo takes it back in memory and says done
    @Uat("WD-46")
    @Test
    fun aForgetTheDiskRefusedIsTakenBackInMemory() {
        val errors = ArrayList<IOException>()
        val disk = RefusingDisk()
        Engines(::load, JvmFiles(folder.newFolder()), onError = { errors += it }, openAppend = disk::open).use { engines ->
            pick(engines)
            assertEquals(listOf("你", "拟"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
            disk.full = true
            val done = engines.learning(InputMethodCatalog.PINYIN, LearningOp.Delete(1, "拟")) as LearningResult.Done
            assertTrue(done.undo != LearningResult.NO_UNDO)
            assertFalse(Offer.FORGET in engines.offers(InputMethodCatalog.PINYIN, 1))
            val said = errors.size
            assertEquals(LearningResult.Done(), engines.learning(InputMethodCatalog.PINYIN, LearningOp.Undo(done.undo)))
            assertEquals("nothing rewritten, nothing more said of the disk", said, errors.size)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            assertEquals(listOf("你", "拟"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
            assertTrue(Offer.FORGET in engines.offers(InputMethodCatalog.PINYIN, 1))
            assertEquals(LearningResult.Unavailable, engines.learning(InputMethodCatalog.PINYIN, LearningOp.Undo(done.undo)))
        }
    }

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

    // the chip says 「没撤销成」, not that the act is no longer the last
    @Uat("WD-46")
    @Test
    fun aForgetOnDiskWhoseRewriteFailsSaysItFailed() {
        val errors = ArrayList<IOException>()
        val dir = folder.newFolder()
        Engines(::load, JvmFiles(dir), onError = { errors += it }).use { engines ->
            pick(engines)
            assertEquals(listOf("你", "拟"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
            val done = engines.learning(InputMethodCatalog.PINYIN, LearningOp.Delete(1, "拟")) as LearningResult.Done
            assertTrue(done.undo != LearningResult.NO_UNDO)
            assertFalse(Offer.FORGET in engines.offers(InputMethodCatalog.PINYIN, 1))
            // a directory where the rewrite is written (".compacting") makes it fail
            File(File(dir, LearnLog.FILE + ".compacting"), "in the way").apply { parentFile.mkdirs() }.writeText("")
            val file = File(dir, LearnLog.FILE).readBytes()
            val said = errors.size
            assertEquals(LearningResult.Failed, engines.learning(InputMethodCatalog.PINYIN, LearningOp.Undo(done.undo)))
            assertEquals("said once", said + 1, errors.size)
            assertFalse("still forgotten", Offer.FORGET in engines.offers(InputMethodCatalog.PINYIN, 1))
            assertTrue(file.contentEquals(File(dir, LearnLog.FILE).readBytes()))
            assertEquals(LearningResult.Saving(saving = true, rewrites = true), engines.learning(InputMethodCatalog.PINYIN, LearningOp.Saving))
            assertEquals(LearningResult.Unavailable, engines.learning(InputMethodCatalog.PINYIN, LearningOp.Undo(done.undo)))
            assertEquals(said + 1, errors.size)
        }
    }

    // the clear fails and clears nothing else, backups and 退回恢复前's copy included
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

    // not saving while the disk stays full, saving again once a word is written, no restart
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

/** The record's disk in a test: [refuseNextWrite], [refuseOpens], [full] and [unreadable] fail it as a phone's disk would. */
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

    /** While [full], a file made anew is refused too, so a rewrite of it throws. */
    fun file(real: LogFile): LogFile = OnDisk(real)

    private inner class OnDisk(val real: LogFile) : LogFile by real {
        override fun create(): LogOutput = if (full) throw IOException("full") else real.create()

        override fun read(): ByteArray = if (unreadable) throw IOException("unreadable") else real.read()

        override fun beside(suffix: String): LogFile = OnDisk(real.beside(suffix))

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
