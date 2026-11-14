/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.control

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.FakeClock
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.LearnRecords
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.corrected
import org.fcitx.fcitx5.android.engine.learn.row
import org.fcitx.fcitx5.android.engine.platform.LogFile
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles
import org.fcitx.fcitx5.android.engine.runtime.RefusingDisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * [LearningControl]: U-88 清掉最近 as though nothing was typed since, U-86 清空学到的 keeping what
 * the user did on purpose, an act taken back as though never done; each a rewrite a start reads
 * back the same.
 */
class LearningControlTest {
    @get:Rule
    val folder = TemporaryFolder()

    // in memory, but where a test needs a disk that refuses: that test passes its own
    private val dir: UserFiles = MemoryFiles()

    private val clock = FakeClock()
    private val errors = ArrayList<IOException>()

    private fun file(dir: UserFiles = this.dir) = dir.file(LearnLog.FILE)

    private fun open(dir: UserFiles = this.dir): LearnLog = LearnLog(file(dir), clock, errors::add, compactAt = 1L shl 30).also { it.open() }

    private fun control(log: LearnLog, legacy: List<LogFile> = emptyList()) = LearningControl({ log }, clock, legacy = { legacy }, onError = errors::add).also { it.attach(log) }

    private fun replayed(dir: UserFiles = this.dir) = LearnLog.replay(file(dir).read())

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
        val asBefore = LearnLog.replay(file().read()).dump()
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
        LearnRecords.FORMAT.read(file().read()) { type, input -> reader.decode(type, input)?.let(records::add) }
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
        val old = dir.file("pinyin.user").apply { createNew() }
        control(log, listOf(old)).clearRecent(LearningOp.Span.TODAY)
        assertEquals(1, log.state.uses("指导"))
        assertFalse(old.exists())
        assertEquals(1, replayed().uses("指导"))
    }

    // review-E-engine M3: a clear killed with the old files set aside: the next open deletes them,
    // losing none of the user's words
    @Test
    fun aClearKilledWithTheOldFilesAsideDeletesThemAtTheNextOpen() {
        open().apply { guide() }.close()
        val files = listOf(dir.file("pinyin.user").apply { createNew() }, dir.file("user.dict").apply { createNew() })
        assertTrue(files[0].renameTo(files[0].beside(".cleared")))
        val log = LearnLog(file(), clock, errors::add)
        val control = LearningControl({ log }, clock, legacy = { files }, onError = errors::add)
        // the next open as LearningFacade.learnLog makes it: what was set aside dropped, then the log opened
        assertTrue(control.resumeDrop())
        control.finishDrop()
        log.open()
        assertEquals(1, log.state.uses("指导"))
        assertTrue(files.none { it.exists() || it.beside(".cleared").exists() })
        assertFalse(control.resumeDrop())
        log.close()
        assertTrue(errors.isEmpty())
    }

    // killed as a whole clear sets pinyin.user aside: the table's log is aside by then, so the resume
    // finishes the whole clear, not a partial one that would leave the log to come back into the record
    @Test
    fun aWholeClearKilledMidwaySetsTheTableLogsAsideFirst() {
        val wubi = dir.file("wubi.user").apply { createNew() }
        val pinyin = dir.file("pinyin.user").apply { createNew() }
        val killing = object : LogFile by pinyin {
            override fun renameTo(dest: LogFile): Boolean = throw IllegalStateException("killed")
        }
        val log = open()
        val clearing = LearningControl({ log }, clock, legacy = { all -> if (all) listOf(killing, wubi) else listOf(killing) }, onError = errors::add)
        assertThrows(IllegalStateException::class.java) { clearing.clearLearned(all = true) }
        assertTrue(wubi.beside(".cleared").exists())
        val control = LearningControl({ log }, clock, legacy = { all -> if (all) listOf(pinyin, wubi) else listOf(pinyin) }, onError = errors::add)
        assertTrue(control.resumeDrop())
        control.finishDrop()
        assertTrue(listOf(pinyin, wubi).none { it.exists() || it.beside(".cleared").exists() })
        assertTrue(errors.isEmpty())
    }

    // review-E-engine S1: a clear the disk refuses is no clear, and says so
    @Test
    fun aClearTheDiskRefusesClearsNothing() {
        val disk = JvmFiles(folder.root)
        val log = open(disk)
        log.guide()
        assertTrue(folder.root.setWritable(false))
        try {
            // root writes all the same: nothing to test there
            assumeFalse(folder.root.canWrite())
            assertTrue(runCatching { control(log).clearLearned(all = false) }.exceptionOrNull() is IOException)
        } finally {
            folder.root.setWritable(true)
        }
        assertEquals(1, log.state.uses("指导"))
        assertEquals(1, replayed(disk).uses("指导"))
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
        file().append().use { it.write(LearnRecords.encode(LearnRecord.Restored(minute + 30))) }
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
        val old = dir.file("pinyin.user").apply { createNew() }
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

    // a partial clear (today's) killed with pinyin.user aside: the next open finishes it, a table's
    // log it never took (not read into the record yet) kept; one a whole clear set aside goes as a whole clear's
    @Test
    fun aKilledClearIsFinishedAsTheClearItWas() {
        val pinyin = dir.file("pinyin.user").apply { createNew() }
        val wubi = dir.file("wubi.user").apply { createNew() }
        val legacy = { all: Boolean -> if (all) listOf(pinyin, wubi) else listOf(pinyin) }
        assertTrue(pinyin.renameTo(pinyin.beside(".cleared")))
        val log = LearnLog(file(), clock, errors::add)
        val control = LearningControl({ log }, clock, legacy = legacy, onError = errors::add)
        assertTrue(control.resumeDrop())
        control.finishDrop()
        assertFalse(pinyin.exists() || pinyin.beside(".cleared").exists())
        assertTrue(wubi.exists())
        // the table's log aside: the whole clear's, pinyin.user with it
        pinyin.createNew()
        assertTrue(wubi.renameTo(wubi.beside(".cleared")))
        assertTrue(control.resumeDrop())
        control.finishDrop()
        assertTrue(listOf(pinyin, wubi).none { it.exists() || it.beside(".cleared").exists() })
        assertTrue(errors.isEmpty())
    }

    // R25 F7925 (decode-3): the record's copies kept aside go with a clear of any span, the older builds' files
    // only with today's; and a clear killed with a copy alone set aside is finished as the shorter clear it may
    // have been (the copies are set aside last), the older builds' files left. Each row on files of its own.
    @Test
    fun theCopiesKeptAsideGoWithAClearOfAnySpanTheOlderFilesWithTodaysAlone() {
        class Scene {
            val dir = MemoryFiles()
            val errors = ArrayList<IOException>()
            val pinyin = dir.file("pinyin.user").apply { createNew() }
            val copies = listOf("learn.log.unreadable", "learn.log.unreadable.1").map { dir.file(it).apply { createNew() } }
            val log = LearnLog(file(dir), clock, errors::add, compactAt = 1L shl 30)
            val control = LearningControl({ log }, clock, legacy = { listOf(pinyin) }, copies = { copies }, onError = errors::add)

            fun gone(files: List<LogFile>) = files.none { it.exists() || it.beside(".cleared").exists() }
        }
        assertRows(
            "a clear of ten minutes: the copies go, the older builds' file stays" to {
                with(Scene()) {
                    log.open()
                    // one typed before the ten minutes stays in the log, one in them goes
                    log.guide()
                    clock.minutes(30)
                    log.guide()
                    control.clearRecent(LearningOp.Span.TEN_MINUTES)
                    log.close()
                    assertTrue(gone(copies))
                    assertTrue(pinyin.exists())
                    assertEquals(emptyList<IOException>(), errors)
                }
            },
            "killed with one copy set aside, the other not yet: finished as the shorter clear, the older file left" to {
                with(Scene()) {
                    assertTrue(copies[0].renameTo(copies[0].beside(".cleared")))
                    assertTrue(control.resumeDrop())
                    control.finishDrop()
                    assertTrue(gone(copies))
                    assertTrue(pinyin.exists())
                    assertFalse(control.resumeDrop())
                    assertEquals(emptyList<IOException>(), errors)
                }
            },
            "today's, killed with the older file aside before the copies: both finished" to {
                with(Scene()) {
                    assertTrue(pinyin.renameTo(pinyin.beside(".cleared")))
                    assertTrue(control.resumeDrop())
                    control.finishDrop()
                    assertTrue(gone(copies + pinyin))
                    assertEquals(emptyList<IOException>(), errors)
                }
            },
        )
    }

    // R25 F7999: a commit the disk refused is in the state alone. A clear of its span found nothing of it in
    // the file and left it offered till the process ended, though told cleared; where part of the span was in
    // the file, the state was read from the file again and it went. Now the state is the file's either way.
    @Uat("WD-46")
    @Test
    fun aClearOfASpanTheDiskRefusedTakesItFromMemoryToo() {
        class Scene {
            val dir = MemoryFiles()
            val clock = FakeClock()
            val disk = RefusingDisk()
            val log = LearnLog(file(dir), clock, compactAt = 1L shl 30, openAppend = disk::open).also { it.open() }
            var told = 0

            fun refused() {
                disk.full = true
                log.guide()
                disk.full = false
            }

            // 指导's uses in memory and in the file, after ten minutes cleared
            fun cleared(): Pair<Int, Int> {
                log.listener = { told++ }
                assertNotNull(LearningControl({ log }, clock).also { it.attach(log) }.clearRecent(LearningOp.Span.TEN_MINUTES))
                return log.state.uses("指导") to replayed(dir).uses("指导")
            }
        }
        assertRows(
            "all of the span refused: nothing to take out of the file, taken from memory" to {
                with(Scene()) {
                    val before = log.guide()
                    clock.minutes(30)
                    refused()
                    assertEquals(2, log.state.uses("指导"))
                    assertEquals(1 to 1, cleared())
                    assertEquals("the user model is told", 1, told)
                    // the state was made anew from the file: what a commit before it changed is not there to put back
                    assertFalse("no commit before it is taken back", log.undo(before.seq))
                }
            },
            "part of the span written, part refused: both gone" to {
                with(Scene()) {
                    log.guide()
                    clock.minutes(30)
                    log.guide()
                    refused()
                    assertEquals(1 to 1, cleared())
                }
            },
            "one refused before the span goes too: the state is the file's, as after any rewrite, and it would not outlast the process" to {
                with(Scene()) {
                    log.guide()
                    refused()
                    clock.minutes(30)
                    refused()
                    assertEquals(1 to 1, cleared())
                }
            },
            "nothing refused, nothing of the span: the state stays as it is, untold" to {
                with(Scene()) {
                    log.guide()
                    clock.minutes(30)
                    assertEquals(1 to 1, cleared())
                    assertEquals(0, told)
                }
            },
        )
    }

    // R25 F8018: a write failed and the file could not be cut back, or opened again: the record takes no more
    // writes, and its file still holds what was typed before that. A clear found the store not rewriting, took
    // that for nothing of the span in the file, and was told done; a start read the span's words back.
    // R25 F8021: nor are the backups told to go first (invalidate), as they were at each try of a clear sure to fail
    @Uat("WD-46")
    @Test
    fun aClearOfARecordThatStoppedTakingWritesFailsWithNothingCleared() {
        class Scene(cutBackFails: Boolean) {
            val dir = MemoryFiles()
            val clock = FakeClock()
            val disk = RefusingDisk()
            val real = file(dir)
            val stuck = object : LogFile by real {
                override fun truncate(length: Long): Unit = throw IOException("cannot cut back")
            }
            val log = LearnLog(if (cutBackFails) stuck else real, clock, compactAt = 1L shl 30, openAppend = disk::open).also { it.open() }
            val control = LearningControl({ log }, clock).also { it.attach(log) }
            val invalidated = ArrayList<Int>()

            init {
                // one of the span written, then one refused and the record not opened again
                log.guide()
                disk.refuseOpens = !cutBackFails
                disk.refuseNextWrite()
                log.guide()
                assertFalse(log.saving)
            }

            // 指导's uses in memory and in the file
            fun uses() = log.state.uses("指导") to replayed(dir).uses("指导")
        }
        val recent: (LearningControl, (Int) -> Unit) -> Unit = { c, invalidate -> c.clearRecent(LearningOp.Span.TEN_MINUTES, invalidate) }
        val learned: (LearningControl, (Int) -> Unit) -> Unit = { c, invalidate -> c.clearLearned(all = false, invalidate) }

        // nothing cleared: the word of the span in the file is still there, and offered; no backup told to go
        fun fails(cutBackFails: Boolean, clear: (LearningControl, (Int) -> Unit) -> Unit) = with(Scene(cutBackFails)) {
            assertThrows(IOException::class.java) { clear(control) { invalidated += it } }
            assertEquals(2 to 1, uses())
            assertEquals(emptyList<Int>(), invalidated)
        }
        assertRows(
            "清掉最近, the file not opened again after the write refused: told failed" to { fails(cutBackFails = false, recent) },
            "清空学到的, the file not opened again: told failed" to { fails(cutBackFails = false, learned) },
            "清掉最近, the file not cut back after the write refused: told failed" to { fails(cutBackFails = true, recent) },
            "清空学到的, the file not cut back: told failed" to { fails(cutBackFails = true, learned) },
        )
    }

    // R25 F8027, F8038: a forget is taken back by the record rewritten without it, and a rewrite that throws is
    // told as failed, once, not as an act no longer the last (「已经撤销不了了」).
    // R25 F8050: a forget the file never took (its own write refused: storage full, or the record stopped) has
    // nothing in the file to drop. It was looked for there, not found, and said no longer to be taken back, the
    // word still deleted; it is taken back in memory, as done as any. Failed is left for the forget on disk
    // whose rewrite throws: here storage full at the rewrite, the record still open (R25 F8056: only a record
    // that stopped was tested, which a check before the rewrite would have passed as well)
    @Uat("WD-46")
    @Test
    fun aForgetTheFileNeverTookIsTakenBackInMemoryAndOneItHoldsFailsWithItsRewrite() {
        assertRows(
            "storage full, the forget's write refused: taken back in memory" to {
                with(RefusedScene()) {
                    disk.full = true
                    val token = forget()
                    assertFalse(log.saving)
                    assertTrue(log.takesRewrites)
                    assertEquals("forgotten in memory alone", 0 to 1, uses())
                    takenBackInMemory(token)
                }
            },
            "the forget's write refused and the file not opened again (the record stopped): taken back in memory" to {
                with(RefusedScene()) {
                    disk.refuseOpens = true
                    disk.refuseNextWrite()
                    val token = forget()
                    assertFalse(log.takesRewrites)
                    assertEquals(0 to 1, uses())
                    takenBackInMemory(token)
                }
            },
            "the forget written, storage full at the rewrite without it: failed, said once, nothing taken back" to {
                with(RefusedScene()) {
                    val token = forget()
                    assertEquals(0 to 0, uses())
                    disk.full = true
                    val forgotten = log.state.dump()
                    val written = file(dir).read()
                    assertEquals(LearningControl.TakenBack.FAILED, control.takeBack(token))
                    assertEquals(1, told.size)
                    assertEquals("still forgotten", forgotten, log.state.dump())
                    assertTrue(written.contentEquals(file(dir).read()))
                    assertTrue("the record still open: a rewrite goes through once there is room", log.takesRewrites)
                    // the act is no longer the last: not tried, nor said, again
                    assertEquals(LearningControl.TakenBack.GONE, control.takeBack(token))
                    assertFalse(control.undo(token))
                    assertEquals(1, told.size)
                }
            },
            "one forget of the act written, the next refused: the one in the file dropped by the rewrite, both taken back" to {
                with(RefusedScene()) {
                    there()
                    val token = control.undoable {
                        log.forget("指导")
                        disk.full = true
                        log.forget("点了")
                    }
                    disk.full = false
                    assertEquals(0 to 0, uses())
                    assertEquals(LearningControl.TakenBack.DONE, control.takeBack(token))
                    assertEquals(1 to 1, uses())
                    assertEquals("the forget refused gone with the rewrite", replayed(dir).dump(), log.state.dump())
                    assertEquals(emptyList<IOException>(), told)
                }
            },
        )
    }

    // R25 F8069, F8070: the other ways a forget taken back fails, each said once, nothing taken back
    @Uat("WD-46")
    @Test
    fun aForgetTakenBackFailsWhereTheFileDoesNotReadOrIsStillFull() {
        assertRows(
            // R25 F8069: the other way to failed, the state not made the file's for the file not reading
            "storage full, the forget's write refused, the file not read to take it back: failed, said once, nothing taken back" to {
                with(RefusedScene()) {
                    disk.full = true
                    val token = forget()
                    disk.unreadable = true
                    val forgotten = log.state.dump()
                    assertEquals(LearningControl.TakenBack.FAILED, control.takeBack(token))
                    assertEquals(1, told.size)
                    assertEquals("still forgotten", forgotten, log.state.dump())
                    assertEquals(LearningControl.TakenBack.GONE, control.takeBack(token))
                    assertEquals(1, told.size)
                }
            },
            // R25 F8070: an act the file took in part (deleteAlike writes a forget a word), the disk still full
            // at the undo: the rewrite without the part it took throws
            "one forget of the act written, the next refused, storage still full at the undo: failed, nothing taken back" to {
                with(RefusedScene()) {
                    there()
                    val token = control.undoable {
                        log.forget("指导")
                        disk.full = true
                        log.forget("点了")
                    }
                    val forgotten = log.state.dump()
                    val written = file(dir).read()
                    assertEquals(LearningControl.TakenBack.FAILED, control.takeBack(token))
                    assertEquals(1, told.size)
                    assertEquals("still forgotten", forgotten, log.state.dump())
                    assertEquals(0 to 0, uses())
                    assertTrue(written.contentEquals(file(dir).read()))
                    assertEquals(LearningControl.TakenBack.GONE, control.takeBack(token))
                    assertEquals(1, told.size)
                }
            },
        )
    }

    // R25 F8066, F8068: taking back an act the file refused makes the state what the file says, which drops
    // whatever else the file refused. A word learned after the disk filled and then deleted is not in the file:
    // it was said taken back, the word still gone and the rest learned since gone with it. Taken back that way
    // only where the act's own records are all that is unwritten; else no longer to be taken back, nothing dropped
    @Uat("WD-46")
    @Test
    fun anActTheFileRefusedIsTakenBackOnlyWhereNothingElseIsUnwritten() {
        assertRows(
            "storage full, a word on disk deleted, nothing else unwritten: taken back, the word there again" to {
                with(RefusedScene()) {
                    there()
                    disk.full = true
                    val token = forget("指导")
                    assertEquals(0 to 1, uses())
                    assertEquals(LearningControl.TakenBack.DONE, control.takeBack(token))
                    assertEquals(1 to 1, uses())
                    assertEquals(replayed(dir).dump(), log.state.dump())
                    assertEquals(emptyList<IOException>(), told)
                }
            },
            "a word learned after the disk filled, then deleted: no longer taken back, the rest learned since kept" to {
                with(RefusedScene()) {
                    there()
                    disk.full = true
                    // in memory alone: 指导 once more, and the word to delete
                    log.guide()
                    log.commit(corrected("zhidao", "直到", "zhi dao", prev = "一直", shown = "知道"))
                    assertEquals(1, log.state.uses("直到"))
                    val token = forget("直到")
                    assertEquals(0, log.state.uses("直到"))
                    gone(token)
                    assertEquals("what the file never took of 指导 is still there", 2 to 1, uses())
                    assertEquals("still deleted: the file has none of it to bring back", 0, log.state.uses("直到"))
                }
            },
            "a word on disk deleted with another learned in memory before: no longer taken back, the other kept" to {
                with(RefusedScene()) {
                    there()
                    disk.full = true
                    log.guide()
                    val token = forget("点了")
                    gone(token)
                    assertEquals(2 to 1, uses())
                }
            },
            // the act is no longer the last either way (attach): the row holds that what came after is not dropped
            "something else learned in memory after the delete: no longer taken back, it is kept" to {
                with(RefusedScene()) {
                    there()
                    disk.full = true
                    val token = forget("点了")
                    log.guide()
                    gone(token)
                    assertEquals(2 to 1, uses())
                }
            },
            "an act the file took in part, something learned in memory before it: no longer taken back, it is kept" to {
                with(RefusedScene()) {
                    there()
                    disk.full = true
                    log.commit(corrected("zhidao", "直到", "zhi dao", prev = "一直", shown = "知道"))
                    disk.full = false
                    val token = control.undoable {
                        log.forget("指导")
                        disk.full = true
                        log.forget("点了")
                    }
                    disk.full = false
                    assertEquals(0 to 0, uses())
                    gone(token)
                    assertEquals(1, log.state.uses("直到"))
                }
            },
        )
    }

    // R25 F8038: what is not a failure of the disk stays "no longer to be taken back", nothing said of the disk
    @Test
    fun anActNoLongerTheLastIsGoneNotFailed() {
        // a word learned and forgotten, the forget the last act: each row its own record
        class Scene {
            val told = ArrayList<IOException>()
            val log = LearnLog(file(MemoryFiles()), FakeClock(), told::add, compactAt = 1L shl 30).also { it.open() }
            val control = LearningControl({ log }, onError = told::add).also { it.attach(log) }
            val token: Long

            init {
                log.guide()
                token = control.undoable { log.forget("指导") }
            }

            fun takenBack(token: Long = this.token) = control.takeBack(token).also { assertEquals(emptyList<IOException>(), told) }
        }
        assertRows(
            "a token of no act" to { assertEquals(LearningControl.TakenBack.GONE, Scene().run { takenBack(token + 1) }) },
            "something learned since" to {
                with(Scene()) {
                    log.guide()
                    assertEquals(LearningControl.TakenBack.GONE, takenBack())
                }
            },
            "the record rewritten since (as a restore does)" to {
                with(Scene()) {
                    assertTrue(log.rewrite { bytes, out -> out.write(bytes); true })
                    assertEquals(LearningControl.TakenBack.GONE, takenBack())
                }
            },
            "the last act still, the record taking writes: taken back" to {
                with(Scene()) {
                    assertEquals(LearningControl.TakenBack.DONE, takenBack())
                    assertEquals(1, log.state.uses("指导"))
                }
            },
        )
    }

    // R25 F8000: a record that holds the time cleared alone (a first day's, no snapshot yet) is rewritten to
    // no record at all. That rewrite was declined (RecordStore, a header alone), the words stayed in the file
    // and in memory, and the user was told they were cleared
    @Uat("PR-21")
    @Test
    fun aClearOfAllTheRecordHoldsEmptiesIt() {
        val log = open()
        log.guide()
        assertEquals(1, log.state.uses("指导"))
        assertNotNull(control(log).clearRecent(LearningOp.Span.TEN_MINUTES))
        assertEquals(0, log.state.uses("指导"))
        assertEquals(0, replayed().uses("指导"))
        // not a header alone, which a start takes for a new log: marked emptied (the bytes: RecordStoreTest)
        assertTrue(file().length() > LearnRecords.FORMAT.header().size)
        assertEquals(emptyList<IOException>(), errors)
        // the record goes on behind the mark of one emptied
        log.guide()
        log.close()
        assertEquals(1, replayed().uses("指导"))
    }
}
