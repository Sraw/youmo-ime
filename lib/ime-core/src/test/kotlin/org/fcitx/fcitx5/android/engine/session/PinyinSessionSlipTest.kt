/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.learn.LearnTo
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.learn.RecordingHabits
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme.Companion.XIAOHE
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.pinyin.TouchBoxes
import org.fcitx.fcitx5.android.engine.pinyin.g14
import org.fcitx.fcitx5.android.engine.pinyin.g14Code
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * A key read as the key next to it, as a session has it (061-neighbour-correction §8): the word so
 * read is learned under the keys meant, the code shows them, and what the keys read as typed is
 * not raised by a slip.
 */
class PinyinSessionSlipTest {

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("你好", -3f, 0f)
                .unigram("他", -2.5f, 0f)
                .unigram("呀", -3f, 0f)
                .unigram("吗", -2.5f, 0f)
                .unigram("们", -3f, 0f)
                .unigram("本", -3.5f, 0f)
                .unigram("我", -2f, 0f)
                .entry("你好", syl("ni", "hao"))
                .entry("他", syl("ta"))
                .entry("呀", syl("ya"))
                .entry("吗", syl("ma"))
                .entry("们", syl("men"))
                .entry("本", syl("ben"))
                .entry("我", syl("wo"))
                .build().toByteArray(),
        ),
    )

    private fun session(segmenter: Segmenter, habits: RecordingHabits, spell: Boolean = false) =
        PinyinSession(data, segmenter, spell = spell, prediction = false, learnTo = LearnTo(habits))

    // [keys] typed, each tapped where [touches] says (null: no place)
    private fun Session.tap(keys: String, touches: List<Touch?>): Snapshot = keys.indices.map { apply(Key(keys[it], touch = touches[it])) }.last()

    private fun Session.pick(text: String) = apply(Action.Pick(candidates(0, 50).indexOfFirst { it.text == text }))

    private fun RecordingHabits.codes() = commits.flatten().map { it.code.keys }

    private val qwerty = { keys: String -> keys.map { if (it in 'a'..'z') TouchBoxes.tap(TouchBoxes.QWERTY, it) else null } }

    @Test
    @Uat("PY-137")
    fun aWordReadThroughASlipIsLearnedUnderTheKeyMeant() {
        val habits = RecordingHabits()
        val s = session(PinyinSegmenter(neighbours = true), habits)
        // s tapped at its left edge, toward a
        val touches = qwerty("nihso").toMutableList().also { it[3] = TouchBoxes.tap(TouchBoxes.QWERTY, 's', dx = -0.45f) }
        val shown = s.tap("nihso", touches)
        assertEquals("你好", shown.candidates[0])
        // the code shows the keys meant
        assertEquals("ni hao", shown.preedit)
        s.pick("你好")
        assertEquals(listOf("nihao"), habits.codes())
    }

    @Test
    @Uat("PY-139")
    fun withNoPlaceAWordReadThroughASlipIsLearnedUnderTheKeyMeantToo() {
        val habits = RecordingHabits()
        val s = session(PinyinSegmenter(neighbours = true), habits)
        val shown = s.tap("nihso", List(5) { null })
        assertEquals("你好", shown.candidates[0])
        s.pick("你好")
        assertEquals(listOf("nihao"), habits.codes())
    }

    @Test
    @Uat("PY-140")
    fun aLegalSlipPickedIsLearnedUnderTheKeysMeantNotThoseTyped() {
        val habits = RecordingHabits()
        val s = session(PinyinSegmenter(neighbours = true), habits)
        val touches = qwerty("tama").toMutableList().also { it[0] = TouchBoxes.tap(TouchBoxes.QWERTY, 't', dx = 0.35f) }
        s.tap("tama", touches)
        s.pick("呀吗")
        assertTrue(habits.codes().toString(), habits.codes().all { "tama" !in it } && habits.codes().any { "yama" in it })
    }

    // §8.3: 呀 picked twice with t tapped toward y (then 吗) is learned under ya; tama tapped so again
    // still has 他 before 呀 (learned under the keys typed, 呀 would be raised there: the first of ta)
    @Test
    @Uat("PY-140")
    fun aLegalSlipPickedRaisesNothingUnderTheKeysTyped() {
        val learner = Learner(data)
        val s = PinyinSession(data, PinyinSegmenter(neighbours = true), pageSize = 20, prediction = false, user = learner.model, learnTo = learner.learnTo())
        val towardY = { qwerty("tama").toMutableList().also { it[0] = TouchBoxes.tap(TouchBoxes.QWERTY, 't', dx = 0.35f) } }
        fun order() = s.tap("tama", towardY()).candidates.let { it.indexOf("他") to it.indexOf("呀") }
        assertTrue(order().let { (ta, ya) -> ya > ta && ta >= 0 })
        s.apply(Action.Reset)
        repeat(2) {
            s.tap("tama", towardY())
            s.pick("呀")
            assertEquals("呀吗", s.apply(Action.Select(0)).commit)
            s.apply(Action.Reset)
            // the habits are looked up under the keys typed (HabitOrder's level, rest and arcs), the picks
            // recorded under the keys meant: once (选一次排前) and twice (连选两次排第一) raise nothing here
            assertTrue("after ${it + 1}", order().let { (ta, ya) -> ya > ta && ta >= 0 })
            s.apply(Action.Reset)
        }
    }

    @Test
    fun aFuzzyReadingIsStillLearnedUnderTheKeysTyped() {
        val habits = RecordingHabits()
        val s = session(PinyinSegmenter(setOf(Fuzzy.L_N), neighbours = true), habits)
        s.tap("lihao", qwerty("lihao"))
        s.pick("你好")
        assertEquals(listOf("lihao"), habits.codes())
    }

    @Test
    @Uat("PY-141")
    fun onTheFourteenKeysTheKeyMeantIsLearnedAsItsCode() {
        val habits = RecordingHabits()
        val s = session(T9Segmenter(abbreviations = false, letters = NineKeyLetters.G14, neighbours = true), habits, spell = true)
        val typed = g14("b e b")
        val touches = typed.map { TouchBoxes.tap(TouchBoxes.G14, it) }.toMutableList()
        touches[0] = TouchBoxes.tap(TouchBoxes.G14, g14Code('b'), dx = 0.45f)
        val shown = s.tap(typed, touches)
        assertTrue(shown.candidates.toString(), "们" in s.candidates(0, 50).map { it.text })
        s.pick("们")
        assertEquals(listOf(g14("m e b")), habits.codes())
    }

    // N2-A: the code shows the syllable as meant, not the keys: 小鹤 tq (tiu spells nothing) with q tapped by a is ta
    @Test
    @Uat("SP-42")
    fun shuangpinsCodeShowsTheSyllableAsMeant() {
        val s = session(ShuangpinSegmenter(XIAOHE, neighbours = true), RecordingHabits())
        val shown = s.tap("tq", listOf(TouchBoxes.tap(TouchBoxes.QWERTY, 't'), TouchBoxes.tap(TouchBoxes.QWERTY, 'q', dx = 0.3f, dy = 0.45f)))
        assertEquals("他", shown.candidates[0])
        assertEquals("ta", shown.preedit)
    }

    // and 九键's: 99 with the second 9 tapped by 6 above it is wo
    @Test
    @Uat("T9-67")
    fun theNineKeysCodeShowsTheSyllableAsMeant() {
        val s = session(T9Segmenter(abbreviations = false, neighbours = true), RecordingHabits(), spell = true)
        val shown = s.tap("99", listOf(TouchBoxes.tap(TouchBoxes.NINE, '9'), TouchBoxes.tap(TouchBoxes.NINE, '9', dy = -0.4f)))
        assertEquals("我", shown.candidates[0])
        assertEquals("wo", shown.preedit)
    }

    // a slip in a commit made in parts is filed by its place in the whole input, so it swaps a key of the part
    // it falls in and of no other: each part's code has the keys meant in its own range alone
    @Test
    fun aCommitInPartsHasEachPartsSlipInItsOwnCode() {
        val towardY = qwerty("tama").toMutableList().also { it[0] = TouchBoxes.tap(TouchBoxes.QWERTY, 't', dx = 0.35f) }
        val first = RecordingHabits()
        session(PinyinSegmenter(neighbours = true), first).run {
            tap("tama", towardY)
            pick("呀")
            pick("吗")
        }
        assertEquals(first.codes().toString(), listOf("ya", "ma"), first.codes().filter { it.length == 2 })
        // n tapped at its right edge, toward m: the second part's (not the last: a syllable still growing reads no slip)
        val towardM = qwerty("tanama").toMutableList().also { it[2] = TouchBoxes.tap(TouchBoxes.QWERTY, 'n', dx = 0.45f) }
        val second = RecordingHabits()
        session(PinyinSegmenter(neighbours = true), second).run {
            tap("tanama", towardM)
            pick("他")
            pick("吗")
            pick("吗")
        }
        assertEquals(second.codes().toString(), listOf("ta", "ma", "ma"), second.codes().filter { it.length == 2 })
    }

    // a pin of a word read through a slip is filed under the keys meant, as its pick is learned: ta tapped on target keeps 他 first
    @Test
    fun aWordReadThroughASlipIsPinnedUnderTheKeysMeant() {
        val habits = RecordingHabits()
        val s = session(PinyinSegmenter(neighbours = true), habits)
        val towardY = qwerty("tama").toMutableList().also { it[0] = TouchBoxes.tap(TouchBoxes.QWERTY, 't', dx = 0.35f) }
        val shown = s.tap("tama", towardY).candidates
        s.apply(Action.Pin(shown.indexOf("呀")))
        assertTrue(habits.pins.toString(), habits.pins.single().contains(":ya ") && habits.pins.single().endsWith(" on"))
    }

    // a word of the user's own (made of picks, twice) read only through legal slips is not raised by being
    // theirs: 全拼's habits read the keys as typed in whole syllables once the graph has a slip
    // (hasOtherReadings; in 0.6.0 全拼's graph was never swapped)
    @Test
    fun aWordOfTheUsersReadOnlyThroughLegalSlipsIsNotRaised() {
        val learner = Learner(data)
        val s = PinyinSession(data, PinyinSegmenter(neighbours = true), pageSize = 20, prediction = false, user = learner.model, learnTo = learner.learnTo())
        repeat(2) {
            s.tap("yayama", qwerty("yayama"))
            s.pick("呀")
            s.pick("呀")
            s.pick("吗")
            s.apply(Action.Reset)
        }
        assertEquals("呀呀吗", s.tap("yayama", qwerty("yayama")).candidates[0])
        s.apply(Action.Reset)
        // both t toward y (not the last syllable: one still growing reads no slip)
        val towardY = qwerty("tatama").toMutableList().also {
            it[0] = TouchBoxes.tap(TouchBoxes.QWERTY, 't', dx = 0.35f)
            it[2] = TouchBoxes.tap(TouchBoxes.QWERTY, 't', dx = 0.35f)
        }
        val shown = s.tap("tatama", towardY).candidates
        // where the decoder has it, behind what the keys read as typed and a slip each
        assertEquals(shown.toString(), 3, shown.indexOf("呀呀吗"))
    }
}
