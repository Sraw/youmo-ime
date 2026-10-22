/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Unknown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * [LearnLog] and [LearnState]: what is learned live is what a start replays, and what a fold at
 * any cut leaves; expiry by typing day; what an undo puts back.
 */
class LearnLogTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val clock = FakeClock()
    private val errors = ArrayList<IOException>()

    private fun file() = File(dir.root, LearnLog.FILE)

    private fun open(compactAt: Long = 1L shl 30) = LearnLog(file(), clock, errors::add, compactAt = compactAt).also { it.open() }

    // a few days of typing: corrections, a word formed, pins, a forget, an undo, a table's and the bar's
    // [skewed] (review-1a-core S6): the clock set back 5 minutes, three days ahead then put right, a zone left
    private fun script(log: LearnLog, skewed: Boolean = false): List<Long> {
        val seqs = ArrayList<Long>()
        fun commit(vararg rows: Row, takes: List<Take> = emptyList()) = log.record(rows.toList(), takes)!!.also { seqs += it.seq }
        commit(row("ni", "你", "ni"))
        clock.minutes(if (skewed) -5 else 1)
        commit(corrected("ni", "拟", "ni", shown = "你"))
        commit(corrected("nizai", "拟再", "ni zai", origin = Row.NONE, shown = "你在"))
        clock.minutes(30)
        log.pin(Code(Kinds.FULL, "dz"), "地址", "di zhi", on = true)
        clock.days(1)
        if (skewed) {
            clock.days(3)
            commit(row("hao", "好", "hao"))
            clock.days(-3)
            clock.zone = -5 * FakeClock.HOUR
        }
        commit(corrected("ni", "拟", "ni", shown = "你"))
        commit(corrected("nizai", "拟再", "ni zai", origin = Row.NONE, shown = "你在"), row("jian", "见", "jian", prev = "拟再"))
        commit(row("64", "你", "ni", kind = Kinds.nineKeys("abc"), how = 'H'), takes = listOf(Take("abc", "64", "ni")))
        clock.minutes(5)
        val undone = commit(corrected("zai", "在", "zai", prev = "拟"))
        log.undo(undone.seq)
        log.forget("见")
        clock.days(2)
        commit(row("ni", "拟", "ni"))
        commit(Row(Row.PICK or Row.WHOLE, Code(Kinds.table("engine-wbx"), "wqiy"), "", Words.of("你好"), 'A', Row.DICTIONARY, ""))
        // blocks under a table's code: one kept, one lifted (review C S9, N7), through a snapshot's row 65 too
        val habits = LogHabits({ log })
        habits.blockUnder(Code(Kinds.table("engine-wbx"), "wqiy"), "你好", on = true)
        habits.blockUnder(Code(Kinds.table("engine-wbx"), "aaaa"), "工", on = true)
        clock.minutes(1)
        habits.blockUnder(Code(Kinds.table("engine-wbx"), "aaaa"), "工", on = false)
        log.pin(Code(Kinds.FULL, "dz"), "地址", "di zhi", on = false)
        return seqs
    }

    private fun replayed() = LearnLog.replay(file().readBytes()).dump()

    @Test
    fun aStartReadsWhatWasLearnedLive() {
        val log = open()
        script(log)
        val live = log.state.dump()
        log.close()
        assertEquals(live, replayed())
        assertEquals(live, open().state.dump())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun theStateIsWhatTheScriptSays() {
        val state = open().also(::script).state
        // corrected to twice in a row with no use between: the first under ni
        assertEquals(Words.of("拟", "ni"), state.firsts[LearnState.FirstKey(Code(Kinds.FULL, "ni"), "")]?.first)
        // committed on two typing days within 30: the user's word
        assertEquals(Mine.FORMED, state.mine["拟再"]?.how)
        // forgotten everywhere
        assertTrue(state.stats.keys.none { it.text == "见" })
        assertTrue(state.pairs.keys.none { it.next == "见" })
        // the undone commit counts nothing
        assertTrue(state.stats.keys.none { it.text == "在" })
        assertEquals(1, state.bars[Take("abc", "64", "ni")]?.count)
        assertTrue(state.pins.isEmpty())
        assertEquals(mapOf(("engine-wbx" to "wqiy") to setOf("你好")), state.tableBlocks)
        assertEquals(3, state.day)
    }

    @Test
    fun aFoldAtAnyCutLeavesTheState() = foldsAtAnyCut(skewed = false)

    @Test
    fun aFoldAtAnyCutLeavesTheStateWhateverTheClockDid() = foldsAtAnyCut(skewed = true)

    private fun foldsAtAnyCut(skewed: Boolean) {
        val log = LearnLog(file(), clock, errors::add, compactAt = 1L shl 30, foldDaily = false).also { it.open() }
        script(log, skewed)
        val live = log.state.dump()
        log.close()
        val bytes = file().readBytes()
        val minutes = LearnLog.replay(bytes).let { (it.lastMinute - 4 * LearnClock.DAY_MINUTES)..(it.lastMinute + 1) }
        var folded = 0
        for (cut in minutes step 97) {
            val out = ByteArrayOutputStream()
            if (!LearnLog.fold(bytes, cut, out)) continue
            folded++
            assertEquals("cut $cut", live, LearnLog.replay(out.toByteArray()).dump())
            // and folded again, later
            val again = ByteArrayOutputStream()
            LearnLog.fold(out.toByteArray(), cut + 2 * LearnClock.DAY_MINUTES, again)
            assertEquals("cut $cut, again", live, LearnLog.replay(again.toByteArray().takeIf { it.isNotEmpty() } ?: out.toByteArray()).dump())
        }
        assertTrue(folded > 10)
    }

    // review-1a-core M2: a record this build cannot read (an undo putting back a row of a later
    // build) is kept as it is through a start, a fold and the next start
    @Test
    fun aRecordNotReadIsKeptThroughAFold() {
        open().also(::script).close()
        val later = LearnRecords.FORMAT.record(LearnRecords.UNDO) {
            writeLong(1)
            repeat(3) { writeInt(0) }
            val undone = LearnRecords.encode(Commit(1, 0, 0, 0, 0, listOf(row("ni", "你", "ni")), emptyList()))
            writeInt(undone.size)
            write(undone)
            writeShort(1)
            writeByte(60)
            writeBoolean(true)
            writeUTF("a later build's")
        }
        file().appendBytes(later)
        val live = open().also { it.close() }.state.dump()
        // a later build's, not this one's failing
        assertTrue(errors.isEmpty())
        assertTrue(contains(file().readBytes(), later))
        val out = ByteArrayOutputStream()
        assertTrue(LearnLog.fold(file().readBytes(), Int.MAX_VALUE, out))
        assertTrue(contains(out.toByteArray(), later))
        assertEquals(live, LearnLog.replay(out.toByteArray()).dump())
    }

    // review-1a-core S2: a row of a later build naming a kind by its id still names it after a
    // fold that names kinds of its own
    @Test
    fun aRowNotReadKeepsItsKindThroughAFold() {
        val bytes = ByteArrayOutputStream()
        bytes.write(LearnRecords.FORMAT.header())
        val writer = LearnRecords.SnapshotWriter(bytes::write, kept = mapOf(5 to "later"))
        writer.write(LearnRecord.Head(-1, 0, 0, 0, 1, 0, -1, ""))
        bytes.write(LearnRecords.FORMAT.record(LATER_ROW) { writeShort(5) })
        bytes.write(LearnRecords.encode(Commit(1, 10, 0, 0, 0, listOf(row("ni", "你", "ni")), emptyList())))
        val out = ByteArrayOutputStream()
        assertTrue(LearnLog.fold(bytes.toByteArray(), 100, out))
        val reader = LearnRecords.Reader()
        val read = ArrayList<LearnRecord>()
        LearnRecords.FORMAT.read(out.toByteArray()) { type, input -> reader.decode(type, input)?.let(read::add) }
        assertEquals("later", reader.kinds[5])
        assertEquals(Kinds.FULL, reader.kinds.entries.single { it.key != 5 }.value)
        assertTrue(read.any { it is Unknown && it.type == LATER_ROW })
    }

    private fun contains(bytes: ByteArray, part: ByteArray) =
        String(bytes, Charsets.ISO_8859_1).contains(String(part, Charsets.ISO_8859_1))

    @Test
    fun nothingOlderThanTheCutIsNoFold() {
        val log = open()
        script(log)
        log.close()
        assertFalse(LearnLog.fold(file().readBytes(), Int.MIN_VALUE, ByteArrayOutputStream()))
    }

    @Test
    fun theLogFoldsInTheBackgroundAsItGrows() = foldsInTheBackground(skewed = false)

    @Test
    fun theLogFoldsInTheBackgroundWhateverTheClockDid() = foldsInTheBackground(skewed = true)

    private fun foldsInTheBackground(skewed: Boolean) {
        val log = LearnLog(file(), clock, errors::add, background = { Thread(it).start() }, compactAt = 2_000).also { it.open() }
        repeat(6) {
            script(log, skewed)
            clock.zone = 8 * FakeClock.HOUR
            clock.days(1)
        }
        val live = log.state.dump()
        log.close()
        assertEquals(live, replayed())
        // folded, not one record a commit
        val records = ArrayList<Byte>()
        LearnRecords.FORMAT.read(file().readBytes()) { type, _ -> records += type }
        assertTrue(LearnRecords.HEAD in records)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun aLogNotFoldedSinceYesterdayFoldsAtTheStart() {
        open().also(::script).close()
        val before = file().length()
        clock.days(1)
        val log = open()
        val live = log.state.dump()
        log.close()
        val records = ArrayList<Byte>()
        LearnRecords.FORMAT.read(file().readBytes()) { type, _ -> records += type }
        assertEquals(LearnRecords.HEAD, records.first())
        assertNotEquals(before, file().length())
        assertEquals(live, replayed())
    }

    @Test
    fun aTailCutShortLosesTheLastCommitOnly() {
        val log = open()
        script(log)
        val beforeLast = log.state.dump()
        val last = log.record(listOf(row("zhong", "中", "zhong")))!!
        log.close()
        val length = file().length()
        RandomAccessFile(file(), "rw").use { it.setLength(length - 3) }
        val reopened = open()
        assertEquals(beforeLast, reopened.state.dump())
        assertNull(reopened.state.stats.keys.find { it.text == "中" })
        assertTrue(last.seq > 0)
    }

    @Test
    fun anUndoAfterItsCommitWasFoldedTakesItBack() {
        val log = open()
        log.record(listOf(row("ni", "你", "ni")))
        val before = log.state.dump()
        val c = log.record(listOf(corrected("ni", "拟", "ni", shown = "你")))!!
        clock.days(1)
        // folded with qp the first kind named, then again with one sorted before it: the kinds' numbers change
        log.fold()
        log.record(listOf(row("aa", "工", kind = "aa:first")))
        clock.days(1)
        log.fold()
        log.undo(c.seq)
        val live = log.state.dump()
        log.close()
        assertEquals(live, replayed())
        assertTrue(log.state.firsts.isEmpty())
        assertNotEquals(before, live)
    }

    // a commit deleted as written is taken back only in the tail: the last hour, or the same day (PLAN §2.5)
    @Test
    fun onlyACommitOfTheTailIsTakenBackAsDeleted() {
        val log = LearnLog(null, clock)
        val a = log.record(listOf(row("ni", "你", "ni")))!!
        clock.minutes(90)
        // ten in the morning and an hour and a half on: the same day
        assertTrue(log.undoRecent(a.seq))
        val b = log.record(listOf(row("ni", "你", "ni")))!!
        clock.minutes(15 * 60)
        // the next day and over an hour: learned as it was
        assertFalse(log.undoRecent(b.seq))
        assertEquals(1, log.state.stats.size)
        // still one of the last few: taken back where asked outright
        assertTrue(log.undo(b.seq))
    }

    // R11 F2799: an Undo too long for a record (the commit and its befores) is not written, and the
    // commit stays one to take back, not silently dropped from the list
    @Test
    fun anUndoTooLongToWriteKeepsTheCommitListed() {
        val errors = ArrayList<IOException>()
        val log = LearnLog(null, clock, errors::add)
        // long keys: in the commit once, and again in each row's Before of its first
        val c = log.record((0 until 100).map { corrected("k".repeat(400) + it, "字", "zi") })!!
        assertEquals(100, c.rows.size)
        assertFalse(log.undo(c.seq))
        assertFalse(log.undo(c.seq))
        // each try said why: the second found the commit still there
        assertEquals(2, errors.size)
        assertEquals(100, log.state.firsts.size)
    }

    // R11 F2688: rows too long for 200 of them in one Absorbed are taken in all, not dropped whole
    @Test
    fun longAbsorbedRowsAreSplitNotDropped() {
        val errors = ArrayList<IOException>()
        val log = LearnLog(null, clock, errors::add)
        val rows = (0 until 200).map { LearnRecord.MineRow("长".repeat(400) + it, Mine("", Mine.TAKEN_IN, 1, 1, 1, 0, 0)) }
        log.absorb(rows)
        assertEquals(emptyList<IOException>(), errors)
        assertEquals(200, log.state.mine.size)
    }

    // F3145: one row too long for any record is left out and told once, not at each halving
    @Test
    fun aRowTooLongForAnyRecordIsToldOnce() {
        val errors = ArrayList<IOException>()
        val log = LearnLog(null, clock, errors::add)
        val mine = Mine("", Mine.TAKEN_IN, 1, 1, 1, 0, 0)
        val rows = (0 until 200).map { LearnRecord.MineRow(if (it == 7) "长".repeat(22_000) else "长$it", mine) }
        log.absorb(rows)
        assertEquals(1, errors.size)
        assertEquals(199, log.state.mine.size)
    }

    // within the hour though the calendar day turned: 23:50's commit deleted at 00:10 is taken back
    @Test
    fun aCommitOfTheLastHourIsTakenBackAcrossMidnight() {
        val log = LearnLog(null, clock)
        // ten in the morning to 23:50
        clock.minutes(13 * 60 + 50)
        val c = log.record(listOf(row("ni", "你", "ni")))!!
        clock.minutes(20)
        assertTrue(log.undoRecent(c.seq))
        assertTrue(log.state.stats.isEmpty())
    }

    // review-1c S4: a rewrite takes nothing kept back any more, and is told; a commit is taken back as itself, not its seq's
    @Test
    fun aRewriteEndsTakingBackAndACommitIsTakenBackAsItself() {
        val log = LearnLog(null, clock)
        val other = LearnLog(null, clock)
        assertNotEquals(log.rewrites, other.rewrites)
        val a = log.record(listOf(row("ni", "你", "ni")))!!
        assertFalse(log.undoRecent(a.seq, a.copy(rows = listOf(row("ma", "吗", "ma")))))
        val before = log.rewrites
        log.rewritten()
        assertNotEquals(before, log.rewrites)
        assertFalse(log.undoRecent(a.seq, a))
        val b = log.record(listOf(row("ni", "你", "ni")))!!
        assertTrue(log.undoRecent(b.seq, b))
    }

    @Test
    fun twoCommitsOnOneFirstUndoneNewestFirst() {
        val log = LearnLog(null, clock)
        val key = LearnState.FirstKey(Code(Kinds.FULL, "ni"), "")
        val a = log.record(listOf(corrected("ni", "拟", "ni")))!!
        val afterA = log.state.firsts[key]
        val b = log.record(listOf(corrected("ni", "拟", "ni")))!!
        assertEquals(Words.of("拟", "ni"), log.state.firsts[key]?.first)
        log.undo(b.seq)
        assertEquals(afterA, log.state.firsts[key])
        log.undo(a.seq)
        assertNull(log.state.firsts[key])
    }

    @Test
    fun anOlderUndoLeavesWhatANewerCommitChanged() {
        val log = LearnLog(null, clock)
        val key = LearnState.FirstKey(Code(Kinds.FULL, "ni"), "")
        val a = log.record(listOf(corrected("ni", "拟", "ni")))!!
        log.record(listOf(corrected("ni", "拟", "ni")))
        val now = log.state.firsts[key]
        log.undo(a.seq)
        // b changed the row last: a's before is not put back over it
        assertEquals(now, log.state.firsts[key])
    }

    @Test
    fun aForgottenWordIsNotPutBackByAnUndo() {
        val log = LearnLog(null, clock)
        val c = log.record(listOf(corrected("ni", "拟", "ni")))!!
        log.forget("拟")
        log.undo(c.seq)
        assertTrue(log.state.firsts.values.none { it.first?.text == "拟" || it.set.any { w -> w.text == "拟" } })
        assertTrue(log.state.stats.isEmpty())
    }

    // an export's words and a table's saved phrases: the user's, with the uses an export counted where the word has none
    @Test
    fun wordsTakenInAreTheUsersWithTheirUsesWhereTheyHaveNone() {
        val log = open()
        log.record(listOf(row("nihao", "你好", "ni hao")))
        val day = log.state.day
        fun mine(text: String) = LearnRecord.MineRow(text, Mine("ni hao", Mine.TAKEN_IN, day, log.state.nextSeq, day, log.state.localDay, 0))
        fun uses(text: String, n: Int) = LearnRecord.WordUseRow(text, "ni hao", DayCounts.of(day to n))
        log.absorb(listOf(mine("拟好"), uses("拟好", 3), uses("你好", 5)))
        assertEquals(Mine.TAKEN_IN, log.state.mine["拟好"]?.how)
        assertEquals(3, log.state.wordUses["拟好"]?.get("ni hao")?.sumFrom(0))
        assertEquals(1, log.state.wordUses["你好"]?.get("ni hao")?.sumFrom(0))
        // taken in again: nothing more
        log.absorb(listOf(uses("拟好", 5)))
        assertEquals(3, log.state.wordUses["拟好"]?.get("ni hao")?.sumFrom(0))
        val live = log.state.dump()
        log.close()
        assertEquals(live, replayed())
    }

    // a text corrected to, of none's: one word; one put in as read: its words, till it is the user's word, then one
    @Test
    fun aCommitCountsItsTextAsOneWordOnceCorrectedToOrTheUsers() {
        val log = LearnLog(null, clock)
        val niZai = Words(listOf(Word("拟", "ni"), Word("再", "zai")))
        fun commit(flags: Int) = log.record(listOf(Row(flags, Code(Kinds.FULL, "nizai"), "", niZai, 'A', Row.NONE, "")))!!
        fun uses() = log.state.wordUses.mapValues { (_, r) -> r.values.sumOf { it.sumFrom(0) } }
        commit(Row.PICK or Row.WHOLE or Row.CORRECTED)
        assertEquals(mapOf("拟再" to 1), uses())
        log.forget("拟再")
        // as read: its words, then, made the user's word by this second, one
        commit(Row.PICK or Row.WHOLE)
        assertEquals(mapOf("拟" to 1, "再" to 1), uses())
        val forming = commit(Row.PICK or Row.WHOLE)
        assertTrue("拟再" in log.state.mine)
        assertEquals(mapOf("拟" to 1, "再" to 1, "拟再" to 1), uses())
        // its undo takes back what it counted
        log.undo(forming.seq)
        assertEquals(mapOf("拟" to 1, "再" to 1), uses())
    }

    @Test
    fun onlyTheLastSixteenMayBeUndone() {
        val log = LearnLog(null, clock)
        val seqs = (1..20).map { log.record(listOf(row("ni", "你", "ni")))!!.seq }
        assertFalse(log.undo(seqs[0]))
        assertTrue(log.undo(seqs[19]))
        assertFalse(log.undo(seqs[19]))
    }

    @Test
    fun whatIsNotUsedForThirtyTypingDaysGoes() {
        val log = LearnLog(null, clock)
        log.record(listOf(corrected("ni", "拟", "ni"), row("zai", "再", "zai")))
        repeat(29) {
            clock.days(1)
            log.record(listOf(row("zai", "再", "zai")))
        }
        // typing day 30: 拟's last use was day 1, 29 days ago
        assertTrue(log.state.stats.keys.any { it.text == "拟" })
        clock.days(1)
        log.record(listOf(row("zai", "再", "zai")))
        assertFalse(log.state.stats.keys.any { it.text == "拟" })
        assertTrue(log.state.firsts.isEmpty())
        // calendar days without typing are no typing days
        clock.days(100)
        log.record(listOf(row("zai", "再", "zai")))
        assertEquals(30, log.state.stats.values.single().uses.sumFrom(Int.MIN_VALUE))
    }

    @Test
    fun anOwnFirstUsedEveryTypingDayStays() {
        val log = LearnLog(null, clock)
        val key = LearnState.FirstKey(Code(Kinds.FULL, "ni"), "")
        log.record(listOf(corrected("ni", "拟", "ni")))
        log.record(listOf(corrected("ni", "拟", "ni")))
        repeat(40) {
            clock.days(1)
            log.record(listOf(row("ni", "拟", "ni")))
        }
        assertEquals(Words.of("拟", "ni"), log.state.firsts[key]?.first)
    }

    @Test
    fun recordsOfALaterBuildSurviveAFold() {
        val log = open()
        log.record(listOf(row("ni", "你", "ni")))
        log.close()
        val later = LearnRecords.FORMAT.record(99) { writeUTF("later") }
        file().appendBytes(later)
        clock.days(1)
        val reopened = open()
        reopened.record(listOf(row("ni", "你", "ni")))
        reopened.fold()
        reopened.close()
        val kept = LearnLog.replay(file().readBytes()).kept
        assertEquals(listOf<LearnRecord>(Unknown(99, later.copyOfRange(5, later.size - 4))), kept)
    }

    @Test
    fun aCommitTooLongLosesItsWordsThenItself() {
        val log = LearnLog(null, clock, errors::add)
        val long = "长".repeat(4000)
        val words = (0 until 6).map { row("x$it", long, flags = Row.WORD) }
        val kept = log.record(listOf(row("x", "你", "ni")) + words)!!
        assertEquals(1, kept.rows.size)
        assertNull(log.record((0 until 6).map { row("x$it", long) }))
        assertEquals(1, errors.size)
    }

    @Test
    fun aRecordAppliesAsItReadsBack() {
        val log = LearnLog(null, clock)
        val c: Commit = log.record(listOf(row("ni", "你", "ni")), listOf(Take("abc", "64", "ni")), Commit.NO_PAIRS)!!
        assertEquals(c, LearnRecords.decode(LearnRecords.encode(c)))
    }

    // review-int-records S1: what replaying a rewrite changed is not handed on with the next record (a forget
    // in the tail would take a word added since out of the user's again)
    @Test
    fun aRewriteHandsTheNextRecordNoneOfItsReplaysChanges() {
        val log = open()
        log.forget("拟")
        val changes = ArrayList<LearnState.Changes>()
        log.listener = { changes += it }
        assertTrue(log.rewrite { bytes, out -> out.write(bytes); true })
        changes.clear()
        log.record(listOf(corrected("ni", "你", "ni")))
        assertEquals(emptyList<String>(), changes.single().forgotten)
        assertEquals(1, changes.single().commits.size)
        log.close()
    }

    // review-int-records S4: words taken in keep their sequence: no commit after them shares one
    @Test
    fun aCommitAfterWordsTakenInIsPastTheirSequence() {
        val log = open()
        val seq = log.state.nextSeq
        val taken = (0 until 3).map { LearnRecord.MineRow("词$it", Mine("ci", Mine.CONVERTED, 0, seq + it, 0, 0, 0)) }
        log.event { minute, _ -> LearnRecord.Absorbed(minute, taken, emptyList()) }
        log.record(listOf(corrected("ni", "你", "ni")))
        assertEquals(seq + 4, log.state.nextSeq)
        log.close()
    }

    // review-uat-fixes S9: what pinyin spells of English's words is made again as they change, not at
    // each pinyin commit
    @Test
    fun englishsWordsChangeWithEnglishsRecordsAloneNotPinyins() {
        val log = LearnLog(null, clock)
        // the first: a typing day begun
        log.record(listOf(row("nihao", "你好", "ni hao")))
        val at = log.state.englishVersion
        log.record(listOf(row("nihao", "你好", "ni hao")))
        assertEquals(at, log.state.englishVersion)
        log.recordEnglish("", "GitHub", "github", LearnRecord.EnCommit.TYPED)
        assertTrue(log.state.englishVersion > at)
    }

    // F2989, F3113: a deferred open of a log whose tail is older than the cut folds it in the
    // background; the conversion merged before the fold landed is merged again, nothing lost
    @Test
    fun aDeferredOpenFoldsAnOldTailAndTheConversionIsMergedAgain() {
        val file = File(dir.root, LearnLog.FILE)
        LearnLog(file, clock).apply { open() }.apply { record(listOf(row("ni", "你", "ni"))) }.close()
        File(dir.root, LearnLog.MARKER).delete()
        // a conversion owed from an earlier start: it may wait days
        File(dir.root, LearnLog.CONVERTING).createNewFile()
        clock.days(3)
        val legacy = object : LearnLog.Legacy {
            override fun into(state: LearnState, clock: LearnClock) {
                state.apply(LearnRecord.WordUseRow("老词", "lao ci", DayCounts.of(1 to 1)))
            }

            override fun marker(state: LearnState) = ConvertedMarker(emptyMap(), emptySet())

            override fun since(was: ConvertedMarker?, state: LearnState, stamp: LearnClock.Stamp): Pair<List<LearnRecord>, ConvertedMarker>? = null

            override val heavy get() = true
        }
        val held = ArrayList<Runnable>()
        val log = LearnLog(file, clock, background = { held += it }).also { it.open(legacy, deferred = true) }
        assertTrue(log.conversionPending)
        val merged = checkNotNull(log.mergeConversion(checkNotNull(log.conversion())()))
        assertTrue(held.isNotEmpty())
        while (held.isNotEmpty()) held.removeAt(0).run()
        // the fold put in place under the merge (at the take's rewrite): only it makes the merge stale
        assertEquals(LearnLog.Taken.STALE, log.takeConversion(merged))
        assertEquals(LearnLog.Taken.DONE, log.takeConversion(checkNotNull(log.mergeConversion(merged.converted))))
        val live = log.state.dump()
        log.close()
        assertTrue(live.contains("你") && live.contains("老词"))
        assertEquals(live, LearnLog.replay(file.readBytes()).dump())
    }

    // F3137: a table's per-code block changes the version a cache of them keys on
    @Test
    fun aTableBlockChangesItsVersion() {
        val state = LearnState()
        val at = state.tableBlocksVersion
        state.apply(LearnRecord.TableBlockRow("wbx", "wq", "你"))
        assertTrue(state.tableBlocksVersion > at)
    }

    // F3195: a snapshot's English pair (a restore, a fold read afresh) changes what English's model reads too
    @Test
    fun anEnglishPairRowChangesEnglishsVersion() {
        val state = LearnState()
        val at = state.englishVersion
        state.apply(LearnRecord.EnPairRow("i", "am", 1, 1))
        assertTrue(state.englishVersion > at)
    }

    private companion object {
        // a row type no build has given (48 is WORD_USE_ROW now): a later build's
        const val LATER_ROW: Byte = 120
    }

    private fun mineOf(reading: String) = Mine(reading, how = 0, since = 1, sinceSeq = 1, lastUsedDay = 1, lastUsedLocalDay = 1, lastBy = 1)

    @Test
    fun aSearchFieldLearnsNoPairs() {
        val log = open()
        log.record(listOf(row("de", "的", "de", prev = "我")), flags = Commit.NO_PREDICT)
        log.record(listOf(row("de", "的", "de", prev = "你")), flags = Commit.NO_PAIRS)
        log.record(listOf(row("de", "的", "de", prev = "他")))
        assertEquals(setOf("他"), log.state.pairs.keys.map { it.prev }.toSet())
        // the word and its order are learned there all the same
        assertEquals(3, log.state.stats.values.sumOf { it.uses.sumFrom(Int.MIN_VALUE) })
    }

    @Test
    fun aWordsLastUseHasItsDate() {
        val log = open()
        log.record(listOf(row("ni", "你", "ni")))
        clock.days(2)
        log.record(listOf(row("ni", "你", "ni")))
        val today = LearnClock.localDay(clock, LearnClock.floorDiv(clock.now(), LearnClock.MINUTE).toInt())
        assertEquals(mapOf(1 to today - 2, 2 to today), log.state.dates)
        assertEquals(log.state.dates, LearnLog.replay(file().readBytes()).dates)
        // a month of typing days on, the first is gone with what was used then
        repeat(30) {
            clock.days(1)
            log.record(listOf(row("hao", "好", "hao")))
        }
        assertFalse(1 in log.state.dates)
    }

    @Test
    fun aRewrittenLogTellsTheWordsThatCameAndWent() {
        val was = LearnState()
        val now = LearnState()
        was.apply(LearnRecord.MineRow("汪小明", mineOf("wang xiao ming")))
        now.apply(LearnRecord.MineRow("点了", mineOf("dian le")))
        val changes = LearnState.Changes.between(was, now)
        assertEquals(mapOf("汪小明" to "wang xiao ming"), changes.removed)
        assertEquals(mapOf("点了" to "dian le"), changes.formed)
        assertTrue(changes.recount)
    }
}
