/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.glide.GlideReading
import org.fcitx.fcitx5.android.engine.glide.GlideSession
import org.fcitx.fcitx5.android.engine.handwriting.handwritingData
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Habits
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.Take
import org.fcitx.fcitx5.android.engine.learn.Words
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Action.Backspace
import org.fcitx.fcitx5.android.engine.session.Action.Context
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.user.UserScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A table's commit deleted at once is taken back; the same keys again are a pick there, and nothing more (U-03, F2655). */
class TableSessionUndoTest {

    // 人 first by the word model (调频 on) and the table (off)
    private val wubi = dictionary("kkkk 人", "kkkk 天", "kkkk 山", "g 高", "hhhh 话", "llll 說")

    private val learner = Learner(handwritingData)
    private val recorded = ArrayList<Row>()
    private val habits = object : Habits by learner.habits {
        override fun record(rows: List<Row>, takes: List<Take>, flags: Int) = learner.habits.record(rows, takes, flags).also { recorded += rows }
    }
    private val kind = Kinds.table("wubi")

    private fun language(simplify: (String) -> String = { it }, emoji: Map<String, List<String>> = emptyMap()): TableLanguage {
        val d = handwritingData
        val readings = Predictor(d.model, d.vocabulary, d.dictionary, learner.model)
        return TableLanguage(
            TextWords(d.model, d.wordIndex, UserScorer(d.model, learner.model)), learner.model,
            reading = { text -> d.wordIndex.find(text).takeIf { it != NO_WORD }?.let(readings::reading) },
            predictor = readings, emoji = { emoji[it].orEmpty() }, habits = habits, kind = kind, simplify = simplify,
        )
    }

    private fun session(
        orderByUse: Boolean = true,
        autoSelect: Boolean = false,
        pinyin: Session? = null,
        language: TableLanguage = language(),
    ) = TableSession(wubi, TableOptions.WUBI.copy(orderByUse = orderByUse, autoSelect = autoSelect), pinyin, language = language)

    private fun Session.pick(keys: String, text: String): String {
        val shown = type(keys).second.candidates
        assertTrue("$text in $shown", text in shown)
        return apply(Select(shown.indexOf(text))).commit
    }

    // [n] Backspaces the app takes, then another action: what they deleted whole taken back
    private fun Session.delete(n: Int) {
        repeat(n) { assertFalse(apply(Backspace).handled) }
        apply(Action.Refine)
    }

    private fun uses(keys: String, text: String) = learner.habits.uses(Code(kind, keys), Words.of(text))

    // TBL-37: 山 picked, 人 put in by mistake and deleted, 山 again: still two running, 山 first
    @Test
    fun aDeletedPickIsTakenBackAndTheRunGoesOn() {
        val t = session()
        t.pick("kkkk", "山")
        t.apply(Context("山", learnFlags = 0))
        assertEquals("人", t.pick("kkkk", "人"))
        assertEquals(1, uses("kkkk", "人"))
        t.delete(1)
        assertEquals(0, uses("kkkk", "人"))
        t.pick("kkkk", "山")
        assertEquals("山", t.type("kkkk").second.candidates.first())
    }

    // TBL-38: 调频 off, the table's order never moves; what was deleted is no use
    @Test
    fun withOrderByUseOffADeletedPickIsNoUse() {
        val t = session(orderByUse = false)
        val before = t.type("kkkk").second.candidates
        t.apply(Context("前", learnFlags = 0))
        t.pick("kkkk", "山")
        t.delete(1)
        assertEquals(0, uses("kkkk", "山"))
        assertFalse("山" in learner.log.state.wordUses)
        assertEquals(before, t.type("kkkk").second.candidates)
    }

    // the same keys again: its own pick row is the correction, nothing else (形码看键)
    @Test
    fun theSameKeysAgainAddNoRow() {
        val t = session()
        t.pick("kkkk", "人")
        t.delete(1)
        recorded.clear()
        t.pick("kkkk", "山")
        assertEquals(1, recorded.count { it.code == Code(kind, "kkkk") })
        assertEquals(0, uses("kkkk", "人"))
        assertEquals(1, learner.habits.corrections(Code(kind, "kkkk"), Words.of("山")))
    }

    // review F2655 MUST: a 联想 pick deleted is taken back, the commit before it stands
    @Test
    fun aDeletedPredictionIsTakenBackAlone() {
        val t = session()
        assertEquals("高", t.apply(Action.Key('g')).let { t.apply(Select(0)) }.commit)
        val offered = t.apply(Action.Refine).candidates
        assertTrue(offered.toString(), "山" in offered)
        assertEquals("山", t.apply(Select(offered.indexOf("山"))).commit)
        assertTrue("高 山" in learner.used())
        t.delete(1)
        assertFalse("高 山" in learner.used())
        assertEquals(1, uses("g", "高"))
    }

    // review F2655 S: a wildcard's commit has its use and pair too: taken back alike
    @Test
    fun aDeletedWildcardCommitIsTakenBack() {
        val t = session()
        t.pick("g", "高")
        t.pick("kzkk", "山")
        assertTrue("高 山" in learner.used())
        t.delete(1)
        assertFalse("高 山" in learner.used())
        assertTrue("高" in learner.used())
    }

    @Test
    fun anEmojiInTheWordsPlaceTakesNothingBack() {
        val t = session(autoSelect = true, language = language(emoji = mapOf("话" to listOf("📢"))))
        val (commit, s) = t.type("hhhh")
        assertEquals("话", commit)
        assertEquals("📢", s.candidates.first())
        assertEquals("📢", t.apply(Select(0)).commit)
        t.delete(1)
        assertEquals(1, uses("hhhh", "话"))
    }

    @Test
    fun aCodeCommittedAsTypedTakesNothingBack() {
        val t = session()
        t.pick("kkkk", "人")
        t.type("kk")
        assertEquals("kk", t.apply(Action.CommitRaw).commit)
        // the code as typed deleted, it ends the run: 人 before it is deleted after, and taken back alone
        t.delete(2)
        assertEquals(1, uses("kkkk", "人"))
    }

    // review F2655 S: a traditional table's commit, read back by the host as written
    @Test
    fun aTraditionalTablesCommitFitsTheField() {
        val t = session(language = language(simplify = { it.replace('說', '说') }))
        assertEquals("說", t.pick("llll", "說"))
        t.apply(Context("說", learnFlags = 0))
        t.delete(1)
        assertEquals(0, uses("llll", "說"))
    }

    // review F2655 S (U-28): a word looked up by pinyin is taken back as full pinyin's
    @Test
    fun aDeletedLookupIsTakenBack() {
        val pinyin = Lookup(learner.habits)
        val t = session(pinyin = pinyin)
        t.type("zren")
        assertEquals("人", t.apply(Select(0)).commit)
        assertTrue("人" in learner.used())
        t.delete(1)
        assertFalse("人" in learner.used())
    }

    // review F3087: a key handed on is in the field; a Backspace deletes it, not the pick before
    @Test
    fun aKeyHandedOnAfterAPickIsWhatABackspaceDeletes() {
        val t = session()
        t.pick("kkkk", "山")
        assertFalse(t.apply(Action.Key(',')).handled)
        t.delete(1)
        assertEquals(1, uses("kkkk", "山"))
    }

    // review F3232: a glide's key no code takes is dropped (GlideSession hands nothing on): a Backspace still takes the pick back
    @Test
    fun aGlidedKeyHandedOnAfterAPickLeavesItToTakeBack() {
        val t = session()
        t.pick("kkkk", "山")
        assertFalse(t.apply(Action.Key(',', glided = true)).handled)
        t.delete(1)
        assertEquals(0, uses("kkkk", "山"))
    }

    // review F3087: a code committed by a key not of the codes, the key then the app's
    @Test
    fun aKeyEndingACodeAndHandedOnIsWhatABackspaceDeletes() {
        val t = session()
        t.type("kkkk")
        val s = t.apply(Action.Key(','))
        assertFalse(s.handled)
        val word = s.commit
        assertTrue(word.isNotEmpty())
        t.delete(1)
        assertEquals(1, uses("kkkk", word))
    }

    // review F3105: a look-up after a message sent learns no pair with the message
    @Test
    fun aLookUpAfterAClearedFieldSaysSo() {
        val pinyin = Lookup(learner.habits)
        val t = session(pinyin = pinyin)
        t.apply(Context("好", cleared = true, learnFlags = 0))
        t.type("z")
        assertTrue(pinyin.context!!.cleared)
    }

    // review F3166: a look-up a key ends is written, then the key: a Backspace deletes the key
    @Test
    fun aLookUpEndedByAKeyIsNotTakenBackByOneBackspace() {
        val pinyin = Lookup(learner.habits)
        val t = session(pinyin = pinyin)
        t.type("zren")
        assertEquals("人", t.apply(Action.Key(',')).commit)
        assertTrue("人" in learner.used())
        t.delete(1)
        assertTrue("人" in learner.used())
    }

    // review F2655 S: a glided code is taken back as a typed one
    @Test
    fun aGlidedCodeIsTakenBack() {
        val table = session()
        val g = GlideSession(table, between = Action.Pick(0), heldBack = { r, _ -> table.heldBack(r.word) }) { _, _ -> listOf(GlideReading("kkkk", "人", 0f)) }
        g.offer(Glide(FloatArray(0), GlideLayout(emptyMap())))
        assertEquals("人", g.apply(Action.Glide).candidates.first())
        assertEquals("人", g.apply(Select(0)).commit)
        g.delete(1)
        assertEquals(0, uses("kkkk", "人"))
    }

    // review F2655 S: a word the glide held back is written, no record of it; the field still known
    @Test
    fun aHeldBackWordTakesNothingBackAndKeepsTheFieldKnown() {
        val table = session()
        val g = GlideSession(table, between = Action.Pick(0), heldBack = { r, _ -> table.heldBack(r.word) }) { _, _ ->
            listOf(GlideReading("kkkk", "人", 0f), GlideReading("xxxx", "话", -1f))
        }
        g.pick("kkkk", "天")
        g.offer(Glide(FloatArray(0), GlideLayout(emptyMap())))
        val shown = g.apply(Action.Glide).candidates
        assertTrue(shown.toString(), "话" in shown)
        assertEquals("话", g.apply(Select(shown.indexOf("话"))).commit)
        // 话 and 天 deleted: 话 had nothing to take back, and ends the run there; 天 is taken back as the field was known
        g.delete(2)
        assertEquals(0, uses("kkkk", "天"))
    }

    // what a table's pinyin session does, as far as the journal goes: 人 for ren, recorded as full pinyin's
    private class Lookup(private val habits: Habits) : Session {
        private val typed = StringBuilder()
        var context: Context? = null
        override var learning = true
        override var lastCommit: Commit? = null

        override fun reads(c: Char) = c in 'a'..'z'
        override fun candidates(from: Int, count: Int) = emptyList<Choice>()
        override fun offers(index: Int) = emptySet<Offer>()

        override fun apply(action: Action): Snapshot {
            var commit = ""
            when (action) {
                is Action.Key -> typed.append(action.char)
                is Context -> context = action
                is Action.Select -> {
                    commit = "人"
                    lastCommit = habits.record(listOf(Row(Row.PICK or Row.WHOLE, Code(Kinds.FULL, typed.toString()), "", Words.of("人", "ren"), 'A', Row.DICTIONARY, "人")))
                    typed.setLength(0)
                }
                else -> typed.setLength(0)
            }
            return Snapshot(commit, typed.toString(), if (typed.isEmpty()) emptyList() else listOf("人"), 0, false, false, handled = true, predicting = false)
        }
    }
}
