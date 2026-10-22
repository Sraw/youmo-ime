/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.english.EnglishUserStore
import org.fcitx.fcitx5.android.engine.english.LegacyEnglishUser
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnWordRow.Companion.ADDED
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnWordRow.Companion.NEVER_CORRECT
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnWordRow.Companion.OWN
import org.fcitx.fcitx5.android.engine.learn.control.LearningControl
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.session.sessionTestData
import org.fcitx.fcitx5.android.engine.session.syl
import org.fcitx.fcitx5.android.engine.table.TableUser.TableLog
import org.fcitx.fcitx5.android.engine.user.UserLog
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * [LegacyConversion] through [LearnLog.open]: once, by the marker; the old files never written;
 * a source that cannot be read leaves nothing, to be tried again.
 */
class LegacyConversionTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val data = sessionTestData()
    private val clock = FakeClock()
    private val errors = ArrayList<IOException>()

    private val model = UserModel(data.dictionary, data.vocabulary).apply {
        // the user's own word, and a pair
        learn(null, listOf(Entry("拟再", syl("ni", "zai"))))
        learn(Entry("你", syl("ni")), listOf(Entry("好", syl("hao"))))
        // a word of a user dictionary typed three times, one added by hand twice: neither the user's to form
        list(Entry("西中", syl("xi", "zhong")))
        repeat(3) { learn(null, listOf(Entry("西中", syl("xi", "zhong")))) }
        listByHand(Entry("中先", syl("zhong", "xian")))
        repeat(2) { learn(null, listOf(Entry("中先", syl("zhong", "xian")))) }
    }

    private val phrases = CustomPhrases.parse("dz,1=地址\nsj,1=#\$time\nNH,1=你好\nyx,2=邮箱\n")

    private fun file(name: String) = File(dir.root, name)

    private fun writeOld(root: File = dir.root) {
        File(root, "wbx.user").writeBytes(
            TableLog.FORMAT.header() + TableLog.picked("wqiy", "你好", 3) + TableLog.saved("aaaa", "工工") +
                TableLog.seen("bbbb", "子子", 2) + TableLog.picked("cccc", "又", 1) + TableLog.forgot("cccc", "又"),
        )
        val english = LegacyEnglishUser()
        EnglishUserStore(File(root, "english.user"), english).apply {
            open()
            repeat(3) { english.learn(null, "teh") }
            english.learn(null, "the")
            repeat(2) { english.learn("the", "youmo") }
            english.add("fcitx")
            close()
        }
    }

    private fun conversion(english: () -> ((String) -> Boolean)? = { { it == "the" || it == "teh" } }) =
        LegacyConversion(dir.root, { model }, phrases, english, { it == "teh" }, errors::add)

    private fun open(convert: LearnLog.Legacy = conversion()) =
        LearnLog(file(LearnLog.FILE), clock, errors::add).also { log -> log.open(convert) }

    @Test
    fun whatOlderBuildsLearnedIsTheFirstSnapshot() {
        writeOld()
        val state = open().state
        assertEquals(setOf("拟再"), state.mine.keys)
        assertEquals(Mine.CONVERTED, state.mine.getValue("拟再").how)
        assertEquals(1, state.pairs[LearnState.PairKey("你", "好", "hao")]?.count)
        assertEquals(Words.of("地址"), state.pins[Code(Kinds.FULL, "dz")])
        // filled in when offered, not of letters, not first: no pins
        assertEquals(1, state.pins.size)
        val wbx = Kinds.table("engine-wbx")
        assertEquals(3, state.stats[LearnState.StatKey(Code(wbx, "wqiy"), "你好", "")]?.uses?.sumFrom(0))
        assertNull(state.stats[LearnState.StatKey(Code(wbx, "cccc"), "又", "")])
        assertNull(state.stats[LearnState.StatKey(Code(wbx, "bbbb"), "子子", "")])
        assertEquals(setOf(Triple("engine-wbx", "aaaa", "工工")), state.tableWords.keys)
        assertEquals("engine-wbx", state.convertedTables)
        assertEquals(LegacyConversion.DAY, state.convertedDay)
        // a misspelling learned is never corrected; a word the data lacks, the user's; the rest counted
        assertEquals(NEVER_CORRECT, state.enWords.getValue("teh").flags)
        assertEquals(OWN or NEVER_CORRECT, state.enWords.getValue("youmo").flags)
        assertEquals(0, state.enWords.getValue("the").flags)
        // added: counted as learned already
        assertEquals(ADDED or OWN or NEVER_CORRECT, state.enWords.getValue("fcitx").flags)
        assertEquals(2, state.enPairs["the" to "youmo"]?.count)
        assertTrue(errors.isEmpty())
    }

    private val niZai = Entry("拟再", syl("ni", "zai"))

    // the older pinyin log, as an older build writes it: 拟再 committed
    private fun writePinyin(root: File = dir.root) = File(root, "pinyin.user").writeBytes(UserLog.header() + UserLog.sentence(null, listOf(niZai)))

    // the older build's English log: [act] done as it does, appended
    private fun english(root: File = dir.root, act: LegacyEnglishUser.() -> Unit) {
        val user = LegacyEnglishUser()
        EnglishUserStore(File(root, "english.user"), user).apply {
            open()
            user.act()
            close()
        }
    }

    // review-1a-app S4, review-1a-r2 M1: back to an older build, which learns and forgets in its logs, then this one again
    @Test
    fun whatAnOlderBuildDidSinceIsTakenIn() {
        writeOld()
        writePinyin()
        open().apply {
            // this build's own: a commit some days on, a pin of the user's, kept whatever the old files say
            clock.days(3)
            record(listOf(row("nihao", "你好", "ni hao")))
            pin(Code(Kinds.FULL, "wo"), "我", "wo", on = true)
        }.close()
        // the older build: 拟再 forgotten and 再见 its own, 子子 saved and 工工 forgotten in the
        // table, youmo forgotten in English, 邮箱 pinned and 地址 unpinned as phrases
        val older = UserModel(data.dictionary, data.vocabulary).apply { learn(null, listOf(Entry("再见", syl("zai", "jian")))) }
        file("pinyin.user").appendBytes(UserLog.forgot(listOf(niZai)) + UserLog.sentence(null, listOf(Entry("再见", syl("zai", "jian")))))
        file("wbx.user").appendBytes(TableLog.saved("bbbb", "子子") + TableLog.forgot("aaaa", "工工"))
        english { forget("youmo") }
        val again = LegacyConversion(dir.root, { older }, CustomPhrases.parse("yx,1=邮箱\n"), { null }, { false }, errors::add)
        val log = open(again)
        val state = log.state
        assertEquals(setOf("再见"), state.mine.keys)
        // as become the user's now, not on the day converted (NIT 3)
        assertEquals(LegacyConversion.DAY + 1, state.mine.getValue("再见").since)
        assertEquals(setOf(Triple("engine-wbx", "bbbb", "子子")), state.tableWords.keys)
        assertNull(state.enWords["youmo"])
        assertEquals(NEVER_CORRECT, state.enWords.getValue("teh").flags)
        // pins are not taken from phrases again: an older build's is a phrase, shown first as one
        assertEquals(setOf(Code(Kinds.FULL, "dz"), Code(Kinds.FULL, "wo")), state.pins.keys)
        // counts are not taken in again: the pair as converted the first time
        assertEquals(1, state.pairs[LearnState.PairKey("你", "好", "hao")]?.count)
        val live = state.dump()
        log.close()
        // as a start reads it, and not taken in twice
        assertEquals(live, LearnLog.replay(file(LearnLog.FILE).readBytes()).dump())
        assertEquals(live, open(again).also { it.close() }.state.dump())
        assertTrue(errors.isEmpty())
    }

    // review-B S6: english.user deleted (全部清空), the next open not after a close: the words never
    // corrected are the log's, kept; the file once back (a backup restored), nothing taken twice
    @Test
    fun theEnglishLogGoneForgetsNoWord() {
        writeOld()
        val old = file("english.user").readBytes()
        open().close()
        file("english.user").delete()
        file("wbx.user").appendBytes(TableLog.picked("wqiy", "你好", 1))
        val state = open().also { it.close() }.state
        assertEquals(NEVER_CORRECT, state.enWords.getValue("teh").flags)
        assertEquals(ADDED or OWN or NEVER_CORRECT, state.enWords.getValue("fcitx").flags)
        file("english.user").writeBytes(old)
        val back = open().also { it.close() }.state
        assertEquals(state.enWords.mapValues { it.value.flags }, back.enWords.mapValues { it.value.flags })
        assertTrue(errors.isEmpty())
    }

    // review-1a-r2 M1: what is gone from the old files without a forget in them is forgotten nowhere,
    // and an older build's forget the log has the word again after is no forget
    @Test
    fun nothingGoneFromTheOldFilesIsForgotten() {
        writeOld()
        writePinyin()
        val before = open().also { it.close() }.state.dump()
        // the older build: 拟再 gone from its model (added by hand, blocked) and in its log forgotten,
        // then typed again; the table's log rewritten without 工工, English's without youmo (faded out)
        val older = UserModel(data.dictionary, data.vocabulary)
        file("pinyin.user").appendBytes(UserLog.forgot(listOf(niZai)) + UserLog.sentence(null, listOf(niZai)))
        file("wbx.user").writeBytes(TableLog.FORMAT.header() + TableLog.picked("wqiy", "你好", 3))
        file("english.user").delete()
        english { learn(null, "the") }
        val again = LegacyConversion(dir.root, { older }, phrases, { null }, { false }, errors::add)
        assertEquals(before, open(again).state.dump())
        assertTrue(errors.isEmpty())
    }

    // review-1a-r2 M1: a process killed opens as one closed: what this build appends to the old logs
    // it writes (a table's words, English's) is read again and changes nothing more
    @Test
    fun aKillAndACloseOpenTheSame() {
        fun run(root: File, close: Boolean): String {
            root.mkdirs()
            writeOld(root)
            writePinyin(root)
            val clock = FakeClock()
            val convert = LegacyConversion(root, { model }, phrases, { { it == "the" || it == "teh" } }, { it == "teh" }, errors::add)
            val log = LearnLog(File(root, LearnLog.FILE), clock, errors::add).also { it.open(convert) }
            // this build: a table saves 子子 and forgets 工工 (everywhere), English learns and forgets youmo
            File(root, "wbx.user").appendBytes(TableLog.saved("bbbb", "子子") + TableLog.forgot("aaaa", "工工"))
            log.forget("工工")
            english(root) {
                learn(null, "fcitx")
                forget("youmo")
            }
            log.forgetEnglish("youmo")
            log.record(listOf(row("nihao", "你好", "ni hao")))
            if (close) log.close()
            clock.days(1)
            return LearnLog(File(root, LearnLog.FILE), clock, errors::add).also { it.open(convert) }.state.dump()
        }
        val killed = run(File(dir.root, "killed"), close = false)
        assertEquals(run(File(dir.root, "closed"), close = true), killed)
        // nothing of the user's forgotten, the table's word saved taken in
        assertTrue("拟再" in killed)
        assertTrue("子子" in killed)
        assertFalse("工工" in killed)
        assertTrue(errors.isEmpty())
    }

    // review-1a-r2 S2: a catch-up that trips on the old files, or a marker that does not read, leaves the log learning to disk
    @Test
    fun aCatchUpThatFailsLeavesTheLogLearning() {
        writeOld()
        open().close()
        file("wbx.user").appendBytes(TableLog.saved("bbbb", "子子"))
        val tripping = object : LearnLog.Legacy by conversion() {
            override fun since(was: ConvertedMarker?, state: LearnState, stamp: LearnClock.Stamp) = error("odd legacy data")
        }
        open(tripping).apply { record(listOf(row("nihao", "你好", "ni hao"))) }.close()
        assertEquals(1, errors.size)
        assertEquals(1, LearnLog.replay(file(LearnLog.FILE).readBytes()).wordUses["你好"]?.size)
        // a marker damaged: what the old files have taken in where the log lacks it, nothing forgotten
        file(LearnLog.MARKER).writeText("FXLR 2 converted\nnot a log line")
        file("wbx.user").appendBytes(TableLog.forgot("aaaa", "工工"))
        val state = open().state
        assertEquals(setOf(Triple("engine-wbx", "aaaa", "工工"), Triple("engine-wbx", "bbbb", "子子")), state.tableWords.keys)
        assertTrue("拟再" in state.mine)
        assertTrue(ConvertedMarker.read(file(LearnLog.MARKER)) != null)
    }

    @Test
    fun theOldFilesAreLeftAsTheyWere() {
        writeOld()
        val before = dir.root.listFiles()!!.associate { it.name to it.readBytes().toList() }
        open().close()
        for ((name, bytes) in before) assertEquals(name, bytes, file(name).readBytes().toList())
        assertTrue(file(LearnLog.MARKER).exists())
    }

    @Test
    fun theSnapshotIsWhatAStartReads() {
        writeOld()
        val live = open().also { it.close() }.state.dump()
        assertEquals(live, LearnLog.replay(file(LearnLog.FILE).readBytes()).dump())
        assertEquals(live, open().state.dump())
    }

    @Test
    fun convertedOnceByTheMarker() {
        writeOld()
        open().apply { forget("拟再") }.close()
        // cleared: the old learning does not come back
        file(LearnLog.FILE).delete()
        assertTrue(open().state.mine.isEmpty())
    }

    @Test
    fun aLogThatDoesNotReadIsKeptAsideNotConvertedAgain() {
        writeOld()
        open().close()
        file(LearnLog.FILE).writeBytes("garbage, not a learning log".toByteArray())
        val state = open().state
        assertTrue(state.mine.isEmpty())
        assertTrue(file(LearnLog.FILE + ".unreadable").exists())
    }

    @Test
    fun aSourceThatCannotBeReadIsTriedAgain() {
        writeOld()
        // a table's log the app cannot read
        file("zrm.user").apply { writeBytes(TableLog.FORMAT.header()) }.setReadable(false)
        val log = open()
        assertTrue(log.state.mine.isEmpty())
        assertFalse(file(LearnLog.MARKER).exists())
        assertTrue(file(LearnLog.FILE).length() <= 8)
        assertEquals(1, errors.size)
        file("zrm.user").setReadable(true)
        errors.clear()
        assertEquals(setOf("拟再"), open().state.mine.keys)
        assertTrue(file(LearnLog.MARKER).exists())
    }

    @Test
    fun aFileOfAnotherFormatIsSkipped() {
        writeOld()
        file("cangjie.user").writeBytes("FXUL\u0000\u0000\u0000\u0001".toByteArray())
        val state = open().state
        assertEquals("engine-wbx", state.convertedTables)
        assertEquals(1, errors.size)
    }

    // a pinyin.user cut short of its header (0 or 3 bytes) reads as none (UserLexicon.legacyModel
    // gives no model): the rest converted all the same, the conversion done (review R11)
    @Test
    fun aPinyinLogCutShortOfItsHeaderLeavesTheRestConverted() {
        for (size in intArrayOf(0, 3)) {
            file(LearnLog.FILE).delete()
            file(LearnLog.MARKER).delete()
            writeOld()
            file("pinyin.user").writeBytes(UserLog.header().copyOf(size))
            val state = open(LegacyConversion(dir.root, { null }, phrases, { { it == "the" || it == "teh" } }, { it == "teh" }, errors::add)).state
            assertTrue(state.mine.isEmpty())
            assertEquals("engine-wbx", state.convertedTables)
            assertEquals(NEVER_CORRECT, state.enWords.getValue("teh").flags)
            assertTrue(file(LearnLog.MARKER).exists())
        }
        assertTrue(errors.isEmpty())
    }

    @Test
    fun noEnglishDataLeavesWordsCounted() {
        writeOld()
        val state = open(conversion { null }).state
        assertEquals(0, state.enWords.getValue("teh").flags)
        assertEquals(ADDED, state.enWords.getValue("fcitx").flags)
    }

    // uat-0.3.1: converted off the typing thread, the log learning meanwhile
    private fun deferred() = LearnLog(file(LearnLog.FILE), clock, errors::add).also { it.open(conversion(), deferred = true) }

    private fun LearnLog.convertAndMerge(): Boolean = takeConversion(checkNotNull(mergeConversion(checkNotNull(conversion())()))) == LearnLog.Taken.DONE

    // what a start reads of the log on disk, as rows: the same rows, the same learning
    private fun onDisk() = LearnLog.replay(file(LearnLog.FILE).readBytes()).let { s -> s.snapshot(s.cut).filterNot { it is LearnRecord.Head }.map { it.toString() }.sorted() }

    @Test
    fun aDeferredConversionLearnsMeanwhileThenMergesIn() {
        writeOld()
        val log = deferred()
        assertTrue(log.conversionPending)
        assertTrue(log.state.mine.isEmpty())
        assertTrue(file(LearnLog.CONVERTING).exists())
        assertFalse(file(LearnLog.MARKER).exists())
        log.record(listOf(row("nihao", "你好", "ni hao")))
        assertTrue(log.convertAndMerge())
        assertFalse(log.conversionPending)
        assertEquals(setOf("拟再"), log.state.mine.keys)
        assertEquals(1, log.state.pairs[LearnState.PairKey("你", "好", "hao")]?.count)
        assertEquals(1, log.state.stats[LearnState.StatKey(Code(Kinds.FULL, "nihao"), "你好", "ni hao")]?.uses?.sumFrom(0))
        assertEquals("engine-wbx", log.state.convertedTables)
        assertTrue(file(LearnLog.MARKER).exists())
        assertFalse(file(LearnLog.CONVERTING).exists())
        log.close()
        // done: a start after neither converts nor owes
        assertFalse(deferred().also { it.close() }.conversionPending)
        assertTrue(errors.isEmpty())
    }

    // review R11 F3209: the conversion landing is no rewrite of the user's: an import undone before it can be put back
    @Test
    fun aBatchUndoneBeforeTheConversionLandsCanStillBePutBack() {
        writeOld()
        val log = deferred()
        val merged = checkNotNull(log.mergeConversion(checkNotNull(log.conversion())()))
        log.imports(ImportedWords.plan(1, "a.txt", 20000, listOf(ImportedWords.Incoming("鲸园", "jing yuan"))) { _, _ -> false })
        log.imports(listOf(LearnRecord.ImportUndo(1)))
        assertEquals(LearnLog.Taken.DONE, log.takeConversion(merged))
        assertTrue(log.state.imports.restore(1).isNotEmpty())
        log.close()
    }

    // review-uat-fixes S5: typed on after the merge was made, the records appended are kept after it
    @Test
    fun aMergeMadeBeforeRecordsWereAppendedTakesThemAlong() {
        writeOld()
        val log = deferred()
        val merged = checkNotNull(log.mergeConversion(checkNotNull(log.conversion())()))
        log.record(listOf(row("nihao", "你好", "ni hao")))
        assertEquals(LearnLog.Taken.DONE, log.takeConversion(merged))
        assertFalse(log.conversionPending)
        assertEquals(1, log.state.stats[LearnState.StatKey(Code(Kinds.FULL, "nihao"), "你好", "ni hao")]?.uses?.sumFrom(0))
        assertEquals(setOf("拟再"), log.state.mine.keys)
        log.close()
        assertEquals(1, LearnLog.replay(file(LearnLog.FILE).readBytes()).stats[LearnState.StatKey(Code(Kinds.FULL, "nihao"), "你好", "ni hao")]?.uses?.sumFrom(0))
    }

    // a merge made before the log was rewritten (a fold, a clear) is not put in place: made again on it
    @Test
    fun aMergeMadeBeforeTheLogWasRewrittenIsMadeAgain() {
        writeOld()
        val log = deferred()
        log.record(listOf(row("nihao", "你好", "ni hao")))
        val merged = checkNotNull(log.mergeConversion(checkNotNull(log.conversion())()))
        clock.days(1)
        assertTrue(log.rewrite { bytes, out -> LearnLog.fold(bytes, LearnClock.cut(clock), out) })
        assertEquals(LearnLog.Taken.STALE, log.takeConversion(merged))
        assertTrue(log.conversionPending)
        assertEquals(LearnLog.Taken.DONE, log.finishConversion(merged))
        assertEquals(1, log.state.stats[LearnState.StatKey(Code(Kinds.FULL, "nihao"), "你好", "ni hao")]?.uses?.sumFrom(0))
        assertEquals(setOf("拟再"), log.state.mine.keys)
    }

    @Test
    fun killedBeforeTheMergeTheConversionIsOwedStillAndWhatWasLearnedKept() {
        writeOld()
        deferred().apply { record(listOf(row("nihao", "你好", "ni hao"))) }.close()
        // the log is not empty now: the mark beside it says the conversion is owed, not done
        val log = deferred()
        assertTrue(log.conversionPending)
        assertTrue(log.convertAndMerge())
        assertEquals(setOf("拟再"), log.state.mine.keys)
        assertEquals(1, log.state.stats[LearnState.StatKey(Code(Kinds.FULL, "nihao"), "你好", "ni hao")]?.uses?.sumFrom(0))
    }

    @Test
    fun killedAfterTheMergeBeforeTheMarkerMergingAgainChangesNothing() {
        writeOld()
        deferred().apply {
            record(listOf(row("nihao", "你好", "ni hao")))
            assertTrue(convertAndMerge())
        }.close()
        val once = onDisk()
        // as if killed between the log put in place and the marker
        file(LearnLog.MARKER).delete()
        file(LearnLog.CONVERTING).writeBytes(ByteArray(0))
        val log = deferred()
        assertTrue(log.conversionPending)
        assertTrue(log.convertAndMerge())
        log.close()
        assertEquals(once, onDisk())
    }

    @Test
    fun aClearWhileTheConversionIsOwedOwesItNoMore() {
        writeOld()
        val log = deferred()
        assertTrue(log.remarkLegacy())
        assertFalse(log.conversionPending)
        assertFalse(file(LearnLog.CONVERTING).exists())
        assertEquals(LearnLog.Taken.NONE, log.finishConversion())
    }

    @Test
    fun nothingOfAnOlderBuildsLogsIsConvertedInPlaceAsBefore() {
        // phrases alone: quick, not deferred
        val log = deferred()
        assertFalse(log.conversionPending)
        assertEquals(Words.of("地址"), log.state.pins[Code(Kinds.FULL, "dz")])
        assertTrue(file(LearnLog.MARKER).exists())
    }

    // review-uat-fixes S1: what was typed while converting counts on top of what the older build
    // learned, and 清掉最近 reaches it, as a tail does
    @Test
    fun whatWasTypedWhileConvertingCountsOnTopAndIsRecentStill() {
        writeOld()
        val log = deferred()
        val wbx = Kinds.table("engine-wbx")
        val key = LearnState.StatKey(Code(wbx, "wqiy"), "你好", "")
        log.record(listOf(row("wqiy", "你好", kind = wbx)))
        assertTrue(log.convertAndMerge())
        assertEquals(4, log.state.stats[key]?.uses?.sumFrom(0))
        LearningControl({ log }, clock).clearRecent(LearningOp.Span.TEN_MINUTES)
        assertEquals(3, log.state.stats[key]?.uses?.sumFrom(0))
        assertEquals(setOf("拟再"), log.state.mine.keys)
        log.close()
        assertEquals(3, LearnLog.replay(file(LearnLog.FILE).readBytes()).stats[key]?.uses?.sumFrom(0))
        assertTrue(errors.isEmpty())
    }

    // review-uat-fixes S2: the tables' logs alone (none retired by a clear), a clear while converting
    // owes the conversion no more, which would bring back what it cleared
    @Test
    fun aClearWithTheTablesLogsAloneOwesTheConversionNoMore() {
        File(dir.root, "wbx.user").writeBytes(TableLog.FORMAT.header() + TableLog.picked("wqiy", "你好", 3))
        val log = deferred()
        assertTrue(log.conversionPending)
        assertTrue(LearningControl({ log }, clock).clearLearned(all = false))
        assertFalse(log.conversionPending)
        assertFalse(file(LearnLog.CONVERTING).exists())
        assertEquals(LearnLog.Taken.NONE, log.finishConversion())
        assertTrue(log.state.stats.isEmpty())
    }

    // review-uat-fixes S4: the mark beside the log not made (a directory not written): nothing thrown at
    // the first key, the log opened as one that cannot be (learning in memory), nothing owed this run
    @Test
    fun noMarkMadeThrowsNothing() {
        writeOld()
        dir.root.setWritable(false)
        // root writes anyway: nothing to test there
        assumeFalse(dir.root.canWrite())
        val log = try {
            deferred()
        } finally {
            dir.root.setWritable(true)
        }
        assertFalse(log.conversionPending)
        assertFalse(log.onDisk)
        assertFalse(errors.isEmpty())
    }

    // review-uat-fixes S5: a log that cannot be rewritten fails, and is owed still
    @Test
    fun aMergeThatCannotBeWrittenIsOwedStill() {
        writeOld()
        val log = deferred()
        val merged = checkNotNull(log.mergeConversion(checkNotNull(log.conversion())()))
        dir.root.setWritable(false)
        try {
            assumeFalse(dir.root.canWrite())
            assertEquals(LearnLog.Taken.FAILED, log.takeConversion(merged))
        } finally {
            dir.root.setWritable(true)
        }
        assertTrue(log.conversionPending)
    }

    // verify-uat-fixes V1: a record read half written by the merge, finished after, is kept whole
    @Test
    fun aRecordReadHalfWrittenIsKeptWhole() {
        writeOld()
        val log = deferred()
        val key = LearnState.StatKey(Code(Kinds.FULL, "nihao"), "你好", "ni hao")
        log.record(listOf(row("nihao", "你好", "ni hao")))
        val before = file(LearnLog.FILE).length().toInt()
        log.record(listOf(row("nihao", "你好", "ni hao")))
        val whole = file(LearnLog.FILE).readBytes()
        // as read in the background while the second was being appended
        file(LearnLog.FILE).writeBytes(whole.copyOf(before + (whole.size - before) / 2))
        val merged = checkNotNull(log.mergeConversion(checkNotNull(log.conversion())()))
        file(LearnLog.FILE).writeBytes(whole)
        assertEquals(LearnLog.Taken.DONE, log.takeConversion(merged))
        assertEquals(2, log.state.stats[key]?.uses?.sumFrom(0))
        log.close()
        assertEquals(2, LearnLog.replay(file(LearnLog.FILE).readBytes()).stats[key]?.uses?.sumFrom(0))
    }
}
