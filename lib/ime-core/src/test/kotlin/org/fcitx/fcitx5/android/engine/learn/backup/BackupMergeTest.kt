/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn.backup

import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.DayCounts
import org.fcitx.fcitx5.android.engine.learn.First
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.BarRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnPairRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnWordRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.FirstRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Head
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.MineRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.PairRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.PendingRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.PinRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.StatRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableWordRow
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.WordUseRow
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.Mine
import org.fcitx.fcitx5.android.engine.learn.Pending
import org.fcitx.fcitx5.android.engine.learn.Take
import org.fcitx.fcitx5.android.engine.learn.Words
import org.fcitx.fcitx5.android.engine.learn.control.LogRewrites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMergeTest {
    private val ni = Code(Kinds.FULL, "ni")
    private val you = Words.of("你", "ni")
    private val draft = Words.of("拟", "ni")

    private fun state(day: Int, vararg rows: LearnRecord) = LearnState().apply {
        apply(Head(-1, 1000, day, 20_000 + day, 100))
        rows.forEach { apply(it) }
    }

    private fun stat(words: Words, vararg uses: Pair<Int, Int>, corrections: List<Pair<Int, Int>> = emptyList()) =
        StatRow(ni, words, DayCounts.of(*uses), DayCounts.of(*corrections.toTypedArray()), 0, 1)

    private fun mine(text: String, reading: String, since: Int, lastUsed: Int) = MineRow(text, Mine(reading, Mine.FORMED, since, since.toLong(), lastUsed, lastUsed, 0))

    private fun LearnState.uses(words: Words, code: Code = ni) = stats[LearnState.StatKey(code, words.text, words.reading)]?.uses.toString()

    @Test
    fun eachDaysCountIsTheLargerOfTheTwo() {
        val phone = state(10, stat(you, 8 to 2, 10 to 1, corrections = listOf(8 to 1)))
        val backup = state(10, stat(you, 8 to 1, 9 to 3), stat(draft, 9 to 1))
        val merged = BackupMerge.merge(phone, backup, from = 1L, sameInstall = true, minute = 2000)
        assertEquals("[8:2,9:3,10:1]", merged.uses(you))
        assertEquals("[9:1]", merged.uses(draft))
        assertEquals("[8:1]", merged.stats.getValue(LearnState.StatKey(ni, "你", "ni")).corrections.toString())
    }

    // U-100: a first, a pin, what was corrected to since: the phone's where both have one
    @Test
    fun whatThePhoneSetWins() {
        val nh = Code(Kinds.FULL, "nh")
        val phone = state(10, FirstRow(ni, "", First(you, emptyList(), 10, 5)), PinRow(nh, "你好", "ni hao"))
        val backup = state(
            10,
            FirstRow(ni, "", First(draft, listOf(you), 10, 7)),
            FirstRow(ni, "我", First(draft, emptyList(), 10, 8)),
            PinRow(nh, "拟好", "ni hao"),
            PinRow(ni, "拟", "ni"),
        )
        val merged = BackupMerge.merge(phone, backup, from = 1L, sameInstall = true, minute = 2000)
        assertEquals(you, merged.firsts.getValue(LearnState.FirstKey(ni, "")).first)
        assertEquals(draft, merged.firsts.getValue(LearnState.FirstKey(ni, "我")).first)
        assertEquals("你好", merged.pins.getValue(nh).text)
        assertEquals("拟", merged.pins.getValue(ni).text)
    }

    @Test
    fun theUsersWordsAreBothSidesAtTheirEarliestAndLatest() {
        val phone = state(10, mine("拟好", "ni hao", 5, 9), mine("好星", "hao xing", 6, 6))
        val backup = state(10, mine("拟好", "ni hao", 2, 4), mine("你拟", "ni ni", 3, 3), mine("好星", "hao xin", 1, 1))
        val merged = BackupMerge.merge(phone, backup, from = 1L, sameInstall = true, minute = 2000)
        assertEquals(setOf("拟好", "好星", "你拟"), merged.mine.keys)
        assertEquals(2 to 9, merged.mine.getValue("拟好").let { it.since to it.lastUsedDay })
        // another reading is another word: the phone's
        assertEquals("hao xing", merged.mine.getValue("好星").reading)
    }

    // PLAN §2.9: another install's days are moved so its last typing day is the phone's
    @Test
    fun anotherInstallsDaysAreTheLastOnesOfThePhone() {
        val phone = state(40, stat(you, 40 to 1))
        val backup = state(100, stat(draft, 95 to 2, 100 to 1), PairRow("你", "好", "hao", 3, 99), stat(Words.of("呢", "ne"), 60 to 1))
        val merged = BackupMerge.merge(phone, backup, from = 2L, sameInstall = false, minute = 2000)
        assertEquals(40, merged.day)
        assertEquals("[35:2,40:1]", merged.uses(draft))
        assertEquals(39, merged.pairs.values.single().lastDay)
        // past the window then, past it now
        assertEquals("null", merged.uses(Words.of("呢", "ne")))
        // the same install's are its own
        assertEquals("[95:2,100:1]", BackupMerge.merge(phone, backup, from = 1L, sameInstall = true, minute = 2000).uses(draft))
    }

    // a new phone has no typing day yet: the backup's last lands on its day 0, and fades from then on
    @Test
    fun aBackupOnANewPhoneEndsOnItsDayNone() {
        val merged = BackupMerge.merge(LearnState(), state(100, stat(draft, 95 to 2, 100 to 1)), from = 2L, sameInstall = false, minute = 2000)
        assertEquals(0, merged.day)
        assertEquals("[-5:2,0:1]", merged.uses(draft))
    }

    @Test
    fun theSameBackupTwiceIsOnce() {
        val phone = state(40, stat(you, 40 to 1), mine("拟好", "ni hao", 30, 39))
        val backup = state(
            100,
            stat(draft, 95 to 2, 100 to 1),
            stat(you, 99 to 4),
            FirstRow(ni, "", First(draft, emptyList(), 100, 7)),
            mine("拟好", "ni hao", 90, 100),
            LearnRecord.NoPredictRow("你", "好"),
            LearnRecord.Unknown(60, byteArrayOf(9)),
        )
        val once = BackupMerge.merge(phone, backup, from = 2L, sameInstall = false, minute = 2000)
        val twice = BackupMerge.merge(once, backup, from = 2L, sameInstall = false, minute = 2000)
        assertEquals(once.dump(), twice.dump())
        assertEquals(listOf(LearnRecord.Unknown(60, byteArrayOf(9))), twice.kept.filterNot { it is LearnRecord.Unknown && it.type == BackupMerge.ORIGIN })
        assertEquals(mapOf(2L to -60), BackupMerge.origins(twice))
    }

    // review-DE-design D-M1: the offset kept, the same backup restored days later lands on the same days
    @Test
    fun theSameBackupDaysLaterIsOnce() {
        val phone = state(40, stat(you, 40 to 1))
        val backup = state(100, stat(draft, 95 to 2, 100 to 1))
        val once = BackupMerge.merge(phone, backup, from = 2L, sameInstall = false, minute = 2000)
        // three typing days on
        val later = LearnState().apply {
            once.snapshot(once.cut).forEach { r -> apply(if (r is Head) r.copy(day = 43) else r) }
        }
        val twice = BackupMerge.merge(later, backup, from = 2L, sameInstall = false, minute = 2100)
        assertEquals("[35:2,40:1]", twice.uses(draft))
        assertEquals(43, twice.day)
        // a newer backup of that install, which the offset would put past the phone's day: its last made the phone's
        val newer = state(110, stat(draft, 110 to 5))
        val third = BackupMerge.merge(twice, newer, from = 2L, sameInstall = false, minute = 2200)
        assertEquals("[35:2,40:1,43:5]", third.uses(draft))
        assertEquals(mapOf(2L to -67), BackupMerge.origins(third))
    }

    // a correction aged out of the window on the phone is not counted again by the same backup restored later
    @Test
    fun aCorrectionAgedOutIsNotCountedTwice() {
        val phone = state(40, stat(you, 40 to 1))
        val backup = state(100, stat(draft, 71 to 1, 100 to 1, corrections = listOf(71 to 2)))
        val once = BackupMerge.merge(phone, backup, from = 2L, sameInstall = false, minute = 2000)
        assertEquals("[11:2]", once.stats.getValue(LearnState.StatKey(ni, "拟", "ni")).corrections.toString())
        val later = LearnState().apply {
            once.snapshot(once.cut).forEach { r -> apply(if (r is Head) r.copy(day = 41) else r) }
            sweepTo(41)
        }
        val twice = BackupMerge.merge(later, backup, from = 2L, sameInstall = false, minute = 2100)
        // the head line (its restore minute) apart
        assertEquals(later.dump().substringAfter('\n'), twice.dump().substringAfter('\n'))
        assertEquals(2, twice.stats.getValue(LearnState.StatKey(ni, "拟", "ni")).older)
    }

    // review-DE-design D-S2: a batch of the same file, day and size is the phone's; another gets a new id, its words with it
    @Test
    fun importBatchesAreThePhonesOrNew() {
        val phone = state(10, LearnRecord.BatchRow(1, "a.txt", 20_000, 2), LearnRecord.ImportedRow(1, 0, "你好", "ni hao"))
        val backup = state(
            10,
            LearnRecord.BatchRow(1, "a.txt", 20_000, 2),
            LearnRecord.ImportedRow(1, 0, "你好", "ni hao"),
            LearnRecord.BatchRow(2, "b.txt", 20_001, 1),
            LearnRecord.ImportedRow(2, 0, "拟好", "ni hao"),
        )
        val merged = BackupMerge.merge(phone, backup, from = 1L, sameInstall = true, minute = 2000)
        assertEquals(
            setOf(
                LearnRecord.BatchRow(1, "a.txt", 20_000, 2),
                LearnRecord.ImportedRow(1, 0, "你好", "ni hao"),
                LearnRecord.BatchRow(2, "b.txt", 20_001, 1),
                LearnRecord.ImportedRow(2, 0, "拟好", "ni hao"),
            ),
            merged.imports.rows().toSet(),
        )
        val shuffled = state(10, LearnRecord.BatchRow(1, "b.txt", 20_001, 1), LearnRecord.ImportedRow(1, 0, "拟好", "ni hao"))
        val again = BackupMerge.merge(phone, shuffled, from = 1L, sameInstall = true, minute = 2000)
        assertTrue(LearnRecord.ImportedRow(2, 0, "拟好", "ni hao") in again.imports.rows())
    }

    // review-G S10: a batch both have, another install's numbering: the backup's word the phone lacks goes in the phone's batch
    @Test
    fun aBatchBothHaveTakesTheBackupsWordsUnderThePhonesId() {
        val phone = state(10, LearnRecord.BatchRow(1, "a.txt", 20_000, 2), LearnRecord.ImportedRow(1, 0, "你好", "ni hao"))
        val backup = state(
            10,
            LearnRecord.BatchRow(5, "a.txt", 20_000, 2),
            LearnRecord.ImportedRow(5, 0, "你好", "ni hao"),
            LearnRecord.ImportedRow(5, 1, "拟好", "ni hao"),
        )
        val merged = BackupMerge.merge(phone, backup, from = 7L, sameInstall = false, minute = 2000)
        assertEquals(
            listOf(
                LearnRecord.BatchRow(1, "a.txt", 20_000, 2),
                LearnRecord.ImportedRow(1, 0, "你好", "ni hao"),
                LearnRecord.ImportedRow(1, 1, "拟好", "ni hao"),
            ),
            merged.imports.rows(),
        )
    }

    // review-G S10: a word in the phone's batch and in another of the backup's stays the phone's, and goes with its batch
    @Test
    fun aWordInBothBatchesIsThePhonesBatchs() {
        val phone = state(10, LearnRecord.BatchRow(1, "a.txt", 20_000, 1), LearnRecord.ImportedRow(1, 0, "你好", "ni hao"))
        val backup = state(10, LearnRecord.BatchRow(1, "b.txt", 20_001, 1), LearnRecord.ImportedRow(1, 0, "你好", "ni hao"))
        val merged = BackupMerge.merge(phone, backup, from = 7L, sameInstall = false, minute = 2000)
        assertEquals(
            listOf(LearnRecord.BatchRow(1, "a.txt", 20_000, 1), LearnRecord.BatchRow(2, "b.txt", 20_001, 1), LearnRecord.ImportedRow(1, 0, "你好", "ni hao")),
            merged.imports.rows(),
        )
        merged.apply(LearnRecord.ImportUndo(1))
        assertFalse(merged.imports.has("你好", "ni hao"))
    }

    // a backup's pin of a text blocked is left out: the block wins
    @Test
    fun aPinOfABlockedTextIsLeftOut() {
        val backup = state(10, PinRow(ni, "拟", "ni"))
        assertTrue(BackupMerge.merge(state(10), backup, from = 1L, sameInstall = true, minute = 2000, blocked = setOf("拟")).pins.isEmpty())
        // the phone's pin of a text the backup blocks too: never both pinned and blocked
        assertTrue(BackupMerge.merge(backup, state(10), from = 1L, sameInstall = true, minute = 2000, blocked = setOf("拟")).pins.isEmpty())
    }

    // English, the nine keys' bar and a table's words: each merged by its rule, the same install's days as they are
    @Test
    fun englishBarAndTableRowsMergeByTheirRules() {
        val take = Take("t9", "64", "ni")
        val phone = state(
            10,
            EnWordRow("teh", EnWordRow.ADDED, DayCounts.of(9 to 1)),
            BarRow(take, 2, 8),
            EnPairRow("i", "am", 1, 10),
            TableWordRow("wbx", "wq", "你", 7),
        )
        val backup = state(
            10,
            EnWordRow("teh", EnWordRow.NEVER_CORRECT, DayCounts.of(9 to 3, 10 to 1)),
            // 不再自动改 in an older backup comes back
            EnWordRow("hte", EnWordRow.NEVER_CORRECT, DayCounts()),
            BarRow(take, 5, 6),
            EnPairRow("i", "am", 3, 9),
            TableWordRow("wbx", "wq", "你", 3),
        )
        val merged = BackupMerge.merge(phone, backup, from = 1L, sameInstall = true, minute = 2000)
        val teh = merged.enWords.getValue("teh")
        assertEquals(EnWordRow.ADDED or EnWordRow.NEVER_CORRECT, teh.flags)
        assertEquals("[9:3,10:1]", teh.uses.toString())
        assertEquals(EnWordRow.NEVER_CORRECT, merged.enWords.getValue("hte").flags)
        assertEquals(5 to 8, merged.bars.getValue(take).let { it.count to it.lastDay })
        assertEquals(3 to 10, merged.enPairs.getValue("i" to "am").let { it.count to it.lastDay })
        // a table's word: since the earlier
        assertEquals(3, merged.tableWords[Triple("wbx", "wq", "你")])
    }

    // PLAN §2.9: every row's typing days of another install moved by the offset
    @Test
    fun anotherInstallsRowsAllMove() {
        val take = Take("t9", "64", "ni")
        val backup = state(
            100,
            PendingRow("拟好", Pending("ni hao", 95, 1)),
            BarRow(take, 2, 99),
            EnWordRow("teh", EnWordRow.NEVER_CORRECT, DayCounts.of(99 to 2)),
            EnPairRow("i", "am", 1, 98),
            TableWordRow("wbx", "wq", "你", 90),
            WordUseRow("拟好", "ni hao", DayCounts.of(97 to 1)),
        )
        val merged = BackupMerge.merge(state(40), backup, from = 2L, sameInstall = false, minute = 2000)
        assertEquals(35, merged.pending.getValue("拟好").firstDay)
        assertEquals(39, merged.bars.getValue(take).lastDay)
        assertEquals("[39:2]", merged.enWords.getValue("teh").uses.toString())
        assertEquals(38, merged.enPairs.getValue("i" to "am").lastDay)
        assertEquals(30, merged.tableWords[Triple("wbx", "wq", "你")])
        assertEquals("[37:1]", merged.wordUses.getValue("拟好").getValue("ni hao").toString())
    }

    // U-86 keeps a table's 调频 counts a restore brought, so it keeps the offset they were moved by:
    // the same backup restored again after it lands on the same days (D-M1)
    @Test
    fun theSameBackupAfterAClearIsOnce() {
        val wbx = Code(Kinds.table("engine-wbx"), "wqiy")
        val words = Words.of("你好", "")
        val backup = state(100, StatRow(wbx, words, DayCounts.of(95 to 2), DayCounts(), 0, 1))
        val once = BackupMerge.merge(state(40), backup, from = 2L, sameInstall = false, minute = 2000)
        val cleared = LearnState().apply {
            for (r in LogRewrites.cleared(once, once.cut, all = false)) LearnLog.applyOrKeep(this, if (r is Head) r.copy(day = 43) else r)
        }
        // the offset kept, however stored: what matters is the second restore landing on the same days
        assertEquals(setOf(2L), BackupMerge.origins(cleared).keys)
        val again = BackupMerge.merge(cleared, backup, from = 2L, sameInstall = false, minute = 2100)
        assertEquals("[35:2]", again.uses(words, wbx))
    }

    @Test
    fun theRestoreIsMarkedAndSequencesGoOnPastBoth() {
        val phone = state(10).apply { nextSeq = 50 }
        val backup = state(10).apply { nextSeq = 900 }
        val merged = BackupMerge.merge(phone, backup, from = 2L, sameInstall = false, minute = 3000)
        assertEquals(3000, merged.restoredMinute)
        assertEquals(900L, merged.nextSeq)
    }

    @Test
    fun theListsTakeInWhatThePhoneLacksThePhoneWinningAClash() {
        val a = ListedWord("幽默", "you'mo")
        val b = ListedWord("坏词", "huai'ci")
        val c = ListedWord("好词", "hao'ci")
        val d = ListedWord("新词", "xin'ci")
        val (add, block) = BackupMerge.lists(listOf(a), listOf(b), added = listOf(a, b, c, d), blocked = listOf(a, c))
        // on both of the backup's lists: blocked, the block winning (C9)
        assertEquals(listOf(d), add)
        // added on the phone, blocked in the backup: added
        assertEquals(listOf(c), block)
    }

    // review-E-engine S11: another install's typing days are moved, the calendar days they were stay; both sides' kept
    @Test
    fun theDatesOfTypingDaysMoveWithThem() {
        val phone = state(40, LearnRecord.DateRow(40, 20400))
        val backup = state(100, LearnRecord.DateRow(100, 20380), LearnRecord.DateRow(95, 20370))
        val merged = BackupMerge.merge(phone, backup, from = 2L, sameInstall = false, minute = 2000)
        assertEquals(20370, merged.dates[35])
        // the same typing day on both: the phone's
        assertEquals(20400, merged.dates[40])
        assertEquals(mapOf(95 to 20370, 100 to 20380), BackupMerge.merge(LearnState(), backup, from = 1L, sameInstall = true, minute = 2000).dates.filterKeys { it >= 95 })
    }
}
