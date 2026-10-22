/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

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
}
