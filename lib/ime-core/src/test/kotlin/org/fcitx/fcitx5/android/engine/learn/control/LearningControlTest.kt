/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.control

import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.ConvertedMarker
import org.fcitx.fcitx5.android.engine.learn.FakeClock
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.LearnRecords
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.Mine
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.corrected
import org.fcitx.fcitx5.android.engine.learn.row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * [LearningControl]: U-88 清掉最近 as though nothing was typed since, U-86 清空学到的 keeping what
 * the user did on purpose, an act taken back as though never done; each a rewrite a start reads
 * back the same.
 */
class LearningControlTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val clock = FakeClock()
    private val errors = ArrayList<IOException>()

    private fun file() = File(dir.root, LearnLog.FILE)

    private fun open(): LearnLog = LearnLog(file(), clock, errors::add, compactAt = 1L shl 30).also { it.open() }

    private fun control(log: LearnLog, legacy: List<File> = emptyList()) = LearningControl({ log }, clock, legacy = { legacy }, onError = errors::add).also { it.attach(log) }

    private fun replayed() = LearnLog.replay(file().readBytes())

    private fun LearnLog.commit(vararg rows: Row): Commit = checkNotNull(record(rows.toList()))

    private val zhidao = Code(Kinds.FULL, "zhidao")

    // 指导 picked over 知道 after 老师
    private fun LearnLog.guide() = commit(corrected("zhidao", "指导", "zhi dao", prev = "老师", shown = "知道"))

    private fun LearnState.uses(text: String) = stats.entries.filter { it.key.code == zhidao && it.key.text == text }.sumOf { it.value.uses.sumFrom(Int.MIN_VALUE) }

    @Test
    fun whatWasTypedTheLastHourIsGoneAsThoughNeverTyped() {
        val log = open()
        repeat(5) { log.guide() }
        clock.days(3)
        // as a start three days later reads it, before the hour typed
        val asBefore = LearnLog.replay(file().readBytes()).dump()
        clock.minutes(10)
        log.guide()
        log.commit(corrected("dl", "点了", "dian le", prev = "我", origin = Row.NONE, shown = "带来"))
        assertEquals(6, log.state.uses("指导"))
        val cut = control(log).clearRecent(LearningOp.Span.HOUR)
        assertEquals(LearnClock.floorDiv(clock.now(), LearnClock.MINUTE).toInt() - 60, cut)
        assertEquals(5, log.state.uses("指导"))
        assertEquals(asBefore, log.state.dump())
        assertEquals(log.state.dump(), replayed().dump())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun whatTheUserDidMeanwhileStays() {
        val log = open()
        val kept = log.guide()
        clock.minutes(30)
        val dropped = log.guide()
        log.pin(Code(Kinds.FULL, "dz"), "地址", "di zhi", on = true)
        log.noPredict("我", "的", on = true)
        // a commit before the cut taken back after it: still taken back; one after it gone with its undo
        log.undo(kept.seq)
        log.undo(dropped.seq)
        control(log).clearRecent(LearningOp.Span.TEN_MINUTES)
        // the first, half an hour before, still taken back
        assertEquals(0, log.state.uses("指导"))
        // nothing to take out is no failure
        assertNotNull(control(log).clearRecent(LearningOp.Span.TEN_MINUTES))
        val state = replayed()
        assertEquals("地址", state.pins[Code(Kinds.FULL, "dz")]?.text)
        assertTrue("我" to "的" in state.noPredict)
    }

    @Test
    fun aCommitGoneTakesItsUndoWithIt() {
        val log = open()
        val first = log.guide()
        clock.minutes(90)
        val second = log.guide()
        log.undo(second.seq)
        log.undo(first.seq)
        control(log).clearRecent(LearningOp.Span.HOUR)
        val records = ArrayList<LearnRecord>()
        val reader = LearnRecords.Reader()
        LearnRecords.FORMAT.read(file().readBytes()) { type, input -> reader.decode(type, input)?.let(records::add) }
        assertEquals(listOf(first.seq), records.filterIsInstance<Commit>().map { it.seq })
        // the undo of the first stays: it was typed before the hour, and taken back
        assertEquals(listOf(first.seq), records.filterIsInstance<LearnRecord.Undo>().map { it.undoneSeq })
        assertEquals(0, log.state.uses("指导"))
    }

    @Test
    fun todayGoesBackToMidnightAndTakesTheOlderBuildsLog() {
        val log = open()
        log.guide()
        // 10:00 in UTC+8; fourteen hours on, just past midnight
        clock.minutes(14 * 60 + 5)
        log.guide()
        log.guide()
        val old = dir.newFile("pinyin.user")
        control(log, listOf(old)).clearRecent(LearningOp.Span.TODAY)
        assertEquals(1, log.state.uses("指导"))
        assertFalse(old.exists())
        assertEquals(1, replayed().uses("指导"))
    }

    // an older build's log, as the learning record converts it: one changed since its marker reads
    // the words it lacks now as forgotten, as a log saying so past what was seen would
    private class Old(var fingerprint: String, var words: List<String>) : LearnLog.Legacy {
        override fun into(state: LearnState, clock: LearnClock) {
            for (w in words) state.apply(LearnRecord.MineRow(w, Mine("zhi dao", Mine.CONVERTED, 1, 0, 1, 0, 0)))
        }

        override fun marker(state: LearnState) = ConvertedMarker(mapOf(fingerprint to ConvertedMarker.Seen(0, 0, 0)), words.toSet())

        override fun since(was: ConvertedMarker?, state: LearnState, stamp: LearnClock.Stamp): Pair<List<LearnRecord>, ConvertedMarker>? {
            val now = marker(state)
            if (was != null && was.seen == now.seen) return null
            return was?.mine.orEmpty().filter { it !in words }.map { LearnRecord.Forget(stamp.minute, stamp.day, it) } to now
        }
    }

    // review-E-engine M3: the old files set aside, the marker not yet written, killed; the next open
    // says them gone without reading them as words forgotten
    @Test
    fun aClearKilledBeforeItsMarkerLosesNoneOfTheUsersWords() {
        val old = Old("pinyin.user:3", listOf("指导"))
        LearnLog(file(), clock, errors::add).also { it.open(old) }.close()
        val files = listOf(dir.newFile("pinyin.user"), dir.newFile("user.dict"))
        assertTrue(files[0].renameTo(File(files[0].path + ".cleared")))
        old.fingerprint = ""
        old.words = emptyList()
        val log = LearnLog(file(), clock, errors::add)
        val control = LearningControl({ log }, clock, legacy = { files }, onError = errors::add)
        assertTrue(control.resumeDrop())
        log.open(old, remark = true)
        control.finishDrop()
        assertTrue("指导" in log.state.mine)
        assertTrue(files.none { it.exists() || File(it.path + ".cleared").exists() })
        assertFalse(control.resumeDrop())
        log.close()
        assertTrue("指导" in LearnLog(file(), clock, errors::add).also { it.open(old) }.state.mine)
        assertTrue(errors.isEmpty())
    }

    // review-E-engine S1: a clear the disk refuses is no clear, and says so
    @Test
    fun aClearTheDiskRefusesClearsNothing() {
        val log = open()
        log.guide()
        assertTrue(dir.root.setWritable(false))
        try {
            // root writes all the same: nothing to test there
            assumeFalse(dir.root.canWrite())
            assertTrue(runCatching { control(log).clearLearned(all = false) }.exceptionOrNull() is IOException)
        } finally {
            dir.root.setWritable(true)
        }
        assertEquals(1, log.state.uses("指导"))
        assertEquals(1, replayed().uses("指导"))
    }

    // review-E-engine S7: U-88 takes the typing, not an English correction the user took back
    @Test
    fun aCorrectionTakenBackStaysThroughAClear() {
        val log = open()
        log.guide()
        clock.minutes(30)
        log.recordEnglish("", "teh", "teh", LearnRecord.EnCommit.REVERTED)
        control(log).clearRecent(LearningOp.Span.TEN_MINUTES)
        val flags = replayed().enWords["teh"]?.flags ?: 0
        assertEquals(LearnRecord.EnWordRow.NEVER_CORRECT, flags and LearnRecord.EnWordRow.NEVER_CORRECT)
        assertTrue(replayed().enWords["teh"]?.uses?.isEmpty() ?: true)
    }

    @Test
    fun clearingNeverGoesBeforeARestore() {
        val log = open()
        log.guide()
        log.close()
        // a restore (stream D's record) at half an hour on, ten minutes before the clear: the hour's cut is no earlier
        val minute = LearnClock.floorDiv(clock.now(), LearnClock.MINUTE).toInt()
        file().appendBytes(LearnRecords.encode(LearnRecord.Restored(minute + 30)))
        clock.minutes(40)
        val again = open()
        again.guide()
        assertEquals(minute + 30, control(again).clearRecent(LearningOp.Span.HOUR))
        assertEquals(1, again.state.uses("指导"))
    }

    @Test
    fun clearingWhatWasLearnedKeepsWhatTheUserDid() {
        val log = open()
        log.commit(corrected("wxm", "汪小明", "wang xiao ming", origin = Row.NONE, shown = "王小明"))
        log.commit(corrected("wxm", "汪小明", "wang xiao ming", origin = Row.NONE, shown = "王小明"))
        log.commit(corrected("dl", "点了", "dian le", prev = "我", origin = Row.NONE, shown = "带来"))
        log.commit(row("wqiy", "你好", kind = Kinds.table("engine-wbx")))
        log.pin(Code(Kinds.FULL, "dz"), "地址", "di zhi", on = true)
        log.noPredict("我", "的", on = true)
        assertTrue("汪小明" in log.state.mine && "点了" in log.state.pending)
        assertTrue(control(log).clearLearned(all = false))
        val state = log.state
        assertEquals(setOf("汪小明"), state.mine.keys)
        assertTrue(state.pending.isEmpty() && state.firsts.isEmpty() && state.pairs.isEmpty() && state.wordUses.isEmpty())
        // a table's order stays, to be restored in its settings (U-28)
        assertEquals(listOf(Kinds.table("engine-wbx")), state.stats.keys.map { it.code.kind })
        assertEquals("地址", state.pins.values.single().text)
        assertTrue("我" to "的" in state.noPredict)
        assertEquals(state.dump(), replayed().dump())
        // and the counters go on: what is typed next is newer
        val next = log.guide()
        assertTrue(next.seq > 4)
    }

    // stream C (review-C S10): 形码调频's order is the table's, kept by U-86 as its counts are; 全部清空 clears it
    @Test
    fun aTablesOrderStaysTillAllIsCleared() {
        val log = open()
        val wbx = Kinds.table("engine-wbx")
        repeat(2) { log.commit(row("wqiy", "你好", flags = Row.PICK or Row.WHOLE or Row.CORRECTED, kind = wbx, how = 'B', shown = "亻")) }
        val firsts = log.state.firsts.keys.filter { it.code.kind == wbx }
        assertTrue(firsts.isNotEmpty())
        assertTrue(control(log).clearLearned(all = false))
        assertEquals(firsts, log.state.firsts.keys.filter { it.code.kind == wbx })
        assertEquals(log.state.dump(), replayed().dump())
        assertTrue(control(log).clearLearned(all = true))
        assertTrue(log.state.firsts.isEmpty())
    }

    // stream C's per-code blocks (TABLE_BLOCK 15, TABLE_BLOCK_ROW 65) are the user's act: U-88 and U-86 keep them, 全部清空 not
    @Test
    fun aTablesBlockUnderACodeStaysTillAllIsCleared() {
        val log = open()
        log.guide()
        clock.minutes(30)
        assertTrue(log.event { minute, _ -> LearnRecord.TableBlock(minute, "engine-wbx", "wqiy", "你", on = true) })
        val blocks = mapOf(("engine-wbx" to "wqiy") to setOf("你"))
        control(log).clearRecent(LearningOp.Span.TEN_MINUTES)
        assertEquals(blocks, log.state.tableBlocks)
        assertTrue(control(log).clearLearned(all = false))
        assertEquals(blocks, log.state.tableBlocks)
        assertEquals(blocks, replayed().tableBlocks)
        assertTrue(control(log).clearLearned(all = true))
        assertTrue(replayed().tableBlocks.isEmpty())
    }

    // a record that did not open: nothing cleared, and the backups, all that is left of it, kept
    @Test
    fun aRecordThatDidNotOpenClearsNothingAndKeepsTheBackups() {
        open().apply { guide() }.close()
        val unopened = LearnLog(file(), clock, errors::add)
        var invalidated = false
        assertFalse(control(unopened).clearLearned(all = false) { invalidated = true })
        assertFalse(invalidated)
        assertEquals(1, replayed().uses("指导"))
    }

    @Test
    fun clearingAllLeavesNothing() {
        val log = open()
        log.guide()
        log.pin(Code(Kinds.FULL, "dz"), "地址", "di zhi", on = true)
        log.commit(row("wqiy", "你好", kind = Kinds.table("engine-wbx")))
        val old = dir.newFile("pinyin.user")
        val day = log.state.day
        assertTrue(control(log, listOf(old)).clearLearned(all = true))
        assertTrue(log.state.isEmpty())
        // HEAD kept (review E-M1): the typing days go on, not back to the first
        assertEquals(day, log.state.day)
        assertEquals(day, replayed().day)
        assertTrue(replayed().isEmpty())
        assertFalse(old.exists())
        // nothing comes back with today's clear
        control(log).clearRecent(LearningOp.Span.TODAY)
        assertTrue(log.state.isEmpty())
    }

    @Test
    fun inMemoryWhatWasLearnedIsClearedButNotByTime() {
        val log = LearnLog(null, clock).also { it.open() }
        log.guide()
        val control = LearningControl({ log }, clock).also { it.attach(log) }
        // what was typed is in the state alone, with no time on it
        assertNull(control.clearRecent(LearningOp.Span.HOUR))
        assertEquals(1, log.state.uses("指导"))
        // a forget has nothing to take it out of
        assertFalse(control.undo(control.undoable { log.forget("指导") }))
        log.guide()
        log.pin(Code(Kinds.FULL, "dz"), "地址", "di zhi", on = true)
        assertTrue(control.clearLearned(all = false))
        assertEquals(0, log.state.uses("指导"))
        assertEquals("地址", log.state.pins.values.single().text)
    }

    @Test
    fun aHeaderAloneIsNeverWritten() {
        val log = open()
        log.guide()
        assertFalse(log.rewrite { _, out -> out.write(LearnRecords.FORMAT.header()).let { true } })
        assertEquals(1, replayed().uses("指导"))
    }

    @Test
    fun theBackupsGoBeforeTheRewrite() {
        val log = open()
        log.guide()
        val told = ArrayList<Int>()
        val length = file().length()
        val control = control(log)
        control.clearRecent(LearningOp.Span.TEN_MINUTES) { cut ->
            told += cut
            // not rewritten yet
            assertEquals(length, file().length())
        }
        control.clearLearned(all = false) { told += it }
        assertEquals(LearningResult.ALL, told.last())
        assertEquals(2, told.size)
    }

    @Test
    fun aFoldRunLateLeavesTheRewrite() {
        val tasks = ArrayList<Runnable>()
        val log = LearnLog(file(), clock, errors::add, background = { tasks += it }).also { it.open() }
        log.guide()
        clock.days(1)
        // the first commit of a day: yesterday's folded, in the background, not run yet
        log.guide()
        assertEquals(1, tasks.size)
        clock.minutes(5)
        log.guide()
        control(log).clearRecent(LearningOp.Span.TEN_MINUTES)
        assertEquals(1, log.state.uses("指导"))
        // the fold, run late, renames nothing over the rewrite
        tasks.single().run()
        log.guide()
        log.close()
        assertEquals(2, open().state.uses("指导"))
        assertTrue(errors.isEmpty())
    }

    // verify-E F1: the marker not written, the files set aside stay, for the next open to remark them and delete them then
    @Test
    fun aMarkerTheDiskRefusesKeepsTheFilesForTheNextOpen() {
        val old = Old("pinyin.user:3", listOf("指导"))
        val log = LearnLog(file(), clock, errors::add).also { it.open(old) }
        val files = listOf(dir.newFile("pinyin.user"), dir.newFile("user.dict"))
        old.fingerprint = ""
        old.words = emptyList()
        // where the marker is written first: a folder with something in it, which no file replaces
        val blocker = File(dir.root, LearnLog.MARKER + ".new").apply { mkdir() }
        File(blocker, "x").createNewFile()
        assertTrue(control(log, files).clearLearned(all = false))
        assertTrue(files.all { !it.exists() && File(it.path + ".cleared").exists() })
        // killed here, never closed
        blocker.deleteRecursively()
        val again = LearnLog(file(), clock, errors::add)
        val control = LearningControl({ again }, clock, legacy = { files }, onError = errors::add)
        assertTrue(control.resumeDrop())
        again.open(old, remark = true)
        assertTrue(again.markedAtOpen)
        control.finishDrop()
        assertTrue("指导" in again.state.mine)
        assertTrue(files.none { it.exists() || File(it.path + ".cleared").exists() })
        again.close()
    }

}
