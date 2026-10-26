/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** [LogHabits]: a fault of the record's turns learning off for the run, reported once (review-1a-app S1). */
class LogHabitsTest {
    @Test
    fun aFaultTurnsLearningOffReportedOnce() {
        val errors = ArrayList<IOException>()
        var opened = 0
        val habits = LogHabits({
            opened++
            error("a bug")
        }, errors::add)
        assertTrue(habits.learns)
        assertNull(habits.record(listOf(row("ni", "你", "ni"))))
        habits.forget("你")
        habits.pin(Code(Kinds.FULL, "ni"), "你", "ni", on = true)
        assertFalse(habits.learns)
        assertEquals(1, errors.size)
        // nothing more asked of the log
        assertEquals(1, opened)
    }

    @Test
    fun aLogThatWorksLearns() {
        val log = LearnLog(null)
        val habits = LogHabits({ log })
        habits.record(listOf(row("ni", "你", "ni")))
        assertTrue(habits.learns)
        assertEquals(1, log.state.stats.size)
    }

    // what sentences keep (U-08): the pins of a kind, and the firsts of several characters, by the prev they follow
    @Test
    fun whatSentencesKeepIsThePinsAndTheFirstsOfSeveralCharacters() {
        val log = LearnLog(null)
        val habits = LogHabits({ log })
        habits.pin(Code(Kinds.FULL, "zhidao"), "指导", "zhi dao", on = true)
        habits.pin(Code(Kinds.FULL, "de"), "得", "de", on = true)
        repeat(2) { habits.record(listOf(corrected("ni", "拟", "ni"), corrected("laoshi", "老是", "lao shi", prev = "我"))) }
        val kept = habits.kept(Kinds.FULL)
        assertEquals(setOf("zhidao/null/指导", "laoshi/我/老是"), kept.map { "${it.code.keys}/${it.prev}/${it.words.text}" }.toSet())
        assertTrue(habits.kept("sp:Ziranma").isEmpty())
        // worked out again once the record changes
        habits.pin(Code(Kinds.FULL, "zhidao"), "指导", "zhi dao", on = false)
        assertEquals(listOf("老是"), habits.kept(Kinds.FULL).map { it.words.text })
    }

    // UAT TBL-33, docs/pinyin.md rule 2: picked over the first, then picked again where it is shown
    // first (by use, 调频's models), the streak goes on: first. Only the original first used restarts it
    @Test
    fun aPickShownFirstKeepsItsStreakTheOriginalFirstRestartsIt() {
        val log = LearnLog(null)
        val habits = LogHabits({ log })
        val code = Code(Kinds.table("engine-wubi"), "fghy")
        fun pick(text: String, corrected: Boolean) = habits.record(listOf(row("fghy", text, kind = code.kind, flags = Row.PICK or Row.WHOLE or (if (corrected) Row.CORRECTED else 0))))
        pick("寸", corrected = true)
        pick("寸", corrected = false)
        assertEquals("寸", habits.first(code, "")?.text)
        pick("寸", corrected = false)
        assertEquals("寸", habits.first(code, "")?.text)
        // a new streak: 雨 corrected to once, then the first's use restarts it
        pick("雨", corrected = true)
        pick("寸", corrected = false)
        pick("雨", corrected = true)
        assertEquals("寸", habits.first(code, "")?.text)
    }

    // the same rule for pinyin (69f8ff9b changed both): with no first yet, the shown first used
    // between two corrections restarts the streak, so neither becomes first
    @Test
    fun pinyinNoFirstTheShownFirstBetweenTwoCorrectionsRestartsTheStreak() {
        val log = LearnLog(null)
        val habits = LogHabits({ log })
        val code = Code(Kinds.FULL, "shi")
        habits.record(listOf(corrected("shi", "式", "shi")))
        habits.record(listOf(row("shi", "是", "shi")))
        habits.record(listOf(corrected("shi", "式", "shi")))
        assertNull(habits.first(code, ""))
        // corrected to again with nothing between: first
        habits.record(listOf(corrected("shi", "式", "shi")))
        assertEquals("式", habits.first(code, "")?.text)
    }

    // a word of the streak used where it is shown first (not corrected), as a sentence's used word
    // (a WORD-level sub-row), counts as picked again: first
    @Test
    fun pinyinAWordOfTheStreakUsedInASentenceBecomesFirst() {
        val log = LearnLog(null)
        val habits = LogHabits({ log })
        val code = Code(Kinds.FULL, "shi")
        habits.record(listOf(corrected("shi", "式", "shi")))
        assertNull(habits.first(code, ""))
        habits.record(listOf(row("shi", "式", "shi", flags = Row.WORD)))
        assertEquals("式", habits.first(code, "")?.text)
    }

    // F2739: under 调频 W is shown first by use; V, the table's own first, picked over it restarts
    // the streak (W needs two picks again) and begins its own, which takes a first learned back
    @Test
    fun theOwnFirstPickedOverAnotherShownFirstRestartsTheStreak() {
        for (kind in listOf(Kinds.table("engine-wubi"), Kinds.FULL)) {
            val log = LearnLog(null)
            val habits = LogHabits({ log })
            val code = Code(kind, "kkkk")
            fun pick(text: String, flags: Int) = habits.record(listOf(row("kkkk", text, kind = kind, flags = Row.PICK or Row.WHOLE or flags)))
            pick("员", Row.CORRECTED)
            pick("叫", Row.CORRECTED or Row.OWN_FIRST)
            // 叫 shown first again: 员 corrected to twice more
            pick("员", Row.CORRECTED)
            assertNull(kind, habits.first(code, ""))
            pick("员", Row.CORRECTED)
            assertEquals(kind, "员", habits.first(code, "")?.text)
            // 叫, its own first, twice over 员 shown first: first again
            pick("叫", Row.CORRECTED or Row.OWN_FIRST)
            assertEquals(kind, "员", habits.first(code, "")?.text)
            pick("叫", Row.CORRECTED or Row.OWN_FIRST)
            assertEquals(kind, "叫", habits.first(code, "")?.text)
        }
    }

    // U-20 (D32 B): full spellings' uses and 简拼's corrections, each within its keyboard; forgotten
    // and 30 typing days on, gone
    @Test
    fun spellingsAcrossOneKeyboardAreCountedThereAlone() {
        val clock = FakeClock()
        val log = LearnLog(null, clock)
        val habits = LogHabits({ log })
        val t9 = Kinds.nineKeys(NineKeyLetters.STANDARD.tag())
        val fenghua = Words.of("奉化", "feng hua")
        repeat(2) { habits.record(listOf(row("fenghua", "奉化", "feng hua"))) }
        habits.record(listOf(row("fengh", "奉化", "feng hua")))
        habits.record(listOf(corrected("dl", "点了", "dian le")))
        habits.record(listOf(row("3364482", "奉化", "feng hua", kind = t9)))
        assertEquals(Habits.Spelled(2, 0), habits.spelled(Kinds.FULL, fenghua))
        assertEquals(Habits.Spelled(0, 1), habits.spelled(Kinds.FULL, Words.of("点了", "dian le")))
        assertEquals(Habits.Spelled(1, 0), habits.spelled(t9, fenghua))
        // 连按 is the nine keys' too
        habits.record(listOf(row("fenghua", "奉化", "feng hua", kind = Kinds.nineKeysMultiTap(NineKeyLetters.STANDARD.tag()))))
        assertEquals(Habits.Spelled(2, 0), habits.spelled(t9, fenghua))
        assertEquals(listOf("点了"), habits.spelledFrom(Kinds.FULL, 'd').map { it.text })
        assertEquals(listOf("奉化"), habits.spelledFrom(t9, '3').map { it.text })
        // used once spelt out, 发挥 moves nothing: not walked
        habits.record(listOf(row("fahui", "发挥", "fa hui")))
        assertEquals(listOf("奉化"), habits.spelledFrom(Kinds.FULL, 'f').map { it.text })
        // only rows U-20 counts are kept by word: a syllable alone and a table's are not (R14 F3761, F3774)
        habits.record(listOf(row("de", "的", "de"), row("dehua", "的话", "de hua", kind = Kinds.table("wubi"))))
        assertTrue(log.state.statsOf("的", "de").isEmpty())
        assertTrue(log.state.statsOf("的话", "de hua").isEmpty())
        assertEquals(1, log.state.statsOf("发挥", "fa hui").size)
        habits.forget("点了")
        assertEquals(Habits.Spelled.NONE, habits.spelled(Kinds.FULL, Words.of("点了", "dian le")))
        // 30 typing days of other commits on: the uses gone
        repeat(LearnState.WINDOW) {
            clock.days(1)
            habits.record(listOf(row("ni", "你", "ni")))
        }
        assertEquals(Habits.Spelled.NONE, habits.spelled(Kinds.FULL, fenghua))
        assertTrue(habits.spelledFrom(Kinds.FULL, 'f').isEmpty())
    }

    // 连按's codes start with letters, the digits' with digits: each finds the other's words (R14 F3731)
    @Test
    fun multiTapAndDigitsFindEachOthersWords() {
        val log = LearnLog(null, FakeClock())
        val habits = LogHabits({ log })
        val tag = NineKeyLetters.STANDARD.tag()
        repeat(2) { habits.record(listOf(row("fenghua", "奉化", "feng hua", kind = Kinds.nineKeysMultiTap(tag)))) }
        assertEquals(listOf("奉化"), habits.spelledFrom(Kinds.nineKeys(tag), '3').map { it.text })
        assertEquals(listOf("奉化"), habits.spelledFrom(Kinds.nineKeysMultiTap(tag), 'f').map { it.text })
        repeat(2) { habits.record(listOf(row("944326", "知道", "zhi dao", kind = Kinds.nineKeys(tag)))) }
        assertEquals(listOf("知道"), habits.spelledFrom(Kinds.nineKeysMultiTap(tag), 'z').map { it.text })
        assertEquals(listOf("知道"), habits.spelledFrom(Kinds.nineKeys(tag), '9').map { it.text })
        // a layout of the user's: f on 2, so 2 finds 奉化 and 3 does not (R14 F3768)
        val custom = NineKeyLetters.parse("abcf,de,ghi,jkl,mno,pqrs,tuv,wxyz").tag()
        repeat(2) { habits.record(listOf(row("2364482", "奉化", "feng hua", kind = Kinds.nineKeys(custom)))) }
        assertEquals(listOf("奉化"), habits.spelledFrom(Kinds.nineKeys(custom), '2').map { it.text })
        assertTrue(habits.spelledFrom(Kinds.nineKeys(custom), '3').isEmpty())
    }

    // grouped 双拼 files a row by its code and looks it up by the code typed, not by the code's letters
    @Test
    fun groupedShuangpinFindsItsWordsByTheCodeTyped() {
        val log = LearnLog(null, FakeClock())
        val habits = LogHabits({ log })
        for (keys in listOf(NineKeyLetters.G18, NineKeyLetters.NINE_SP)) {
            val kind = Kinds.shuangpinKeys("Ziranma", keys.tag())
            // 自然码 feng hua: fg hw
            val fenghua = keys.typings("fghw").first()
            repeat(2) { habits.record(listOf(row(fenghua, "奉化", "feng hua", kind = kind))) }
            assertEquals(listOf("奉化"), habits.spelledFrom(kind, fenghua.first()).map { it.text })
        }
    }

    // a correction under 简拼 lifts till its row is swept: 30 typing days with no use of it there (R14 F3751)
    @Test
    fun aCorrectionsFloorGoesWithItsRow() {
        val clock = FakeClock()
        val log = LearnLog(null, clock)
        val habits = LogHabits({ log })
        val dianle = Words.of("点了", "dian le")
        habits.record(listOf(corrected("dl", "点了", "dian le")))
        assertEquals(Habits.Spelled(0, 1), habits.spelled(Kinds.FULL, dianle))
        repeat(LearnState.WINDOW) {
            clock.days(1)
            habits.record(listOf(row("ni", "你", "ni")))
        }
        assertEquals(Habits.Spelled.NONE, habits.spelled(Kinds.FULL, dianle))
        assertTrue(habits.spelledFrom(Kinds.FULL, 'd').isEmpty())
    }
}
