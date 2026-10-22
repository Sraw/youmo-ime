/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.glide.GlideReading
import org.fcitx.fcitx5.android.engine.glide.GlideSession
import org.fcitx.fcitx5.android.engine.learn.Habits
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.LearnTo
import org.fcitx.fcitx5.android.engine.learn.LogHabits
import org.fcitx.fcitx5.android.engine.learn.RecordingHabits
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.simplifierOf
import org.fcitx.fcitx5.android.engine.learn.Take
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.session.Action.Backspace
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What [PinyinSession] writes to the learning record, keyboard by keyboard (impl/design-1a.md §3); nothing it ranks by. */
class PinyinSessionLearnLogTest {
    private val data = sessionTestData()
    private val habits = RecordingHabits()
    private val nineKeys = Kinds.nineKeys(NineKeyLetters.STANDARD.tag())

    private fun session(
        segmenter: Segmenter = PinyinSegmenter(),
        spell: Boolean = false,
        kind: String = Kinds.FULL,
        user: UserModel? = UserModel(data.dictionary, data.vocabulary),
        phrases: PhraseBook = PhraseBook(CustomPhrases.parse("")) {},
        prediction: Boolean = false,
    ) = PinyinSession(
        data, segmenter, spell = spell, prediction = prediction, user = user, pageSize = 20, phraseBook = phrases,
        learnTo = LearnTo(habits, kind, simplifierOf('點' to '点', '發' to '发')::simplify),
    )

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    private fun Session.pick(text: String) = apply(Action.Pick(candidates(0, 50).indexOfFirst { it.text == text }.also { check(it >= 0) { text } }))

    private fun rows(rows: List<Row> = habits.last) =
        rows.map { "${it.code}|${it.prev}|${it.words}|${it.how}${if (it.corrected) "*" else ""}|${it.flags and Row.LEVELS}" }

    @Test
    fun theFirstPickedIsOneRow() {
        val s = session()
        s.type("nihao")
        assertEquals("你好", s.apply(Select(0)).commit)
        // the first shown, 你｜好: its words each used too
        assertEquals(listOf("qp:nihao||你好(ni hao)|A|3", "qp:ni||你(ni)|A|4", "qp:hao|你|好(hao)|A|4"), rows())
    }

    @Test
    fun piecesAreEachPickedAndCorrectedWhole() {
        val s = session()
        s.type("nizai")
        s.pick("拟")
        assertEquals("拟再", s.pick("再").commit)
        val r = rows()
        assertTrue(r.toString(), "qp:ni||拟(ni)|C*|5" in r)
        assertTrue(r.toString(), r.any { it.startsWith("qp:zai|拟|再(zai)|A|") })
        assertTrue(r.toString(), "qp:nizai||拟再(ni zai)|C*|2" in r)
        assertEquals(3, r.size)
    }

    @Test
    fun aCommitFollowsTheWordBefore() {
        val s = session()
        s.type("ni")
        s.apply(Select(0))
        s.type("hao")
        s.apply(Select(0))
        assertEquals("你", habits.last.first().prev)
        // the app's text, traditional: read simplified, its last word
        s.apply(Action.Context("我點"))
        s.type("zai")
        s.apply(Select(0))
        assertEquals("点", habits.last.first().prev)
        // a field that cleared itself: nothing before
        s.apply(Action.Context("你好", cleared = true))
        s.type("zai")
        s.apply(Select(0))
        assertEquals("", habits.last.first().prev)
        // punctuation ends it
        s.apply(Action.Context("你好，"))
        s.type("zai")
        s.apply(Select(0))
        assertEquals("", habits.last.first().prev)
    }

    @Test
    fun aMessageSentIsNoWordBeforeThoughTheFieldStillEndsWithIt() {
        val s = session()
        s.type("nihao")
        s.apply(Select(0))
        // the field cleared itself with the text the session committed still reported (no 联想 shown
        // to reset first): the shortcut for an unmoved cursor must not keep the message (F2652)
        s.apply(Action.Context("你好", cleared = true))
        s.type("zai")
        s.apply(Select(0))
        assertEquals("", habits.last.first().prev)
        // nor a backspace in the empty field bring it back (F2704)
        s.apply(Action.Context("你好再", cleared = true))
        s.apply(Action.Backspace)
        s.type("zai")
        s.apply(Select(0))
        assertEquals("", habits.last.first().prev)
    }

    @Test
    fun aKeyNotReadCommitsTheFirstAsA() {
        val s = session()
        s.type("nihao")
        assertEquals("你好", s.apply(Key(',')).commit.removeSuffix(","))
        // the first shown, 你｜好: its words each used too
        assertEquals(listOf("qp:nihao||你好(ni hao)|A|3", "qp:ni||你(ni)|A|4", "qp:hao|你|好(hao)|A|4"), rows())
    }

    @Test
    fun lettersCommittedAsTypedAreOneRowUncorrected() {
        val s = session()
        s.type("nihao")
        s.apply(Action.CommitRaw)
        assertEquals(listOf("qp:nihao||nihao|F|3"), rows())
    }

    @Test
    fun shuangpinIsFiledUnderItsScheme() {
        val s = session(ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), spell = true, kind = Kinds.shuangpin("Ziranma"))
        s.type("nihk")
        assertEquals("你好", s.apply(Select(0)).commit)
        assertEquals(listOf("sp:Ziranma:nihk||你好(ni hao)|A|3", "sp:Ziranma:ni||你(ni)|A|4", "sp:Ziranma:hk|你|好(hao)|A|4"), rows())
    }

    @Test
    fun theNineKeysAreFiledUnderTheirDigits() {
        val s = session(T9Segmenter(abbreviations = false), spell = true)
        s.type("64426")
        assertEquals("你好", s.apply(Select(0)).commit)
        assertEquals(listOf("$nineKeys:64426||你好(ni hao)|A|3", "$nineKeys:64||你(ni)|A|4", "$nineKeys:426|你|好(hao)|A|4"), rows())
        assertEquals(listOf(emptyList<Take>()), habits.takes)
    }

    @Test
    fun aSyllableTakenKeepsItsDigitsAndIsH() {
        val s = session(T9Segmenter(abbreviations = false), spell = true)
        s.type("64426")
        s.apply(Action.Syllable(0))
        assertTrue(s.pressedInStep)
        s.apply(Select(0))
        val r = habits.last.single { it.whole }
        assertEquals("$nineKeys:64426", r.code.toString())
        assertEquals('H', r.how)
        assertEquals(listOf(Take(NineKeyLetters.STANDARD.tag(), "64", "ni")), habits.takes.last())
    }

    @Test
    fun aSyllableGivenBackIsItsDigitsAgain() {
        val s = session(T9Segmenter(abbreviations = false), spell = true)
        s.type("64426")
        s.apply(Action.Syllable(1))
        s.apply(Backspace)
        assertTrue(s.pressedInStep)
        // then a digit goes
        s.apply(Backspace)
        s.type("6")
        assertTrue(s.pressedInStep)
        s.apply(Select(0))
        assertEquals("$nineKeys:64426", habits.last.single { it.whole }.code.toString())
        assertEquals(emptyList<Take>(), habits.takes.last())
    }

    // review-1a-core S5: a key typed with the cursor in a syllable taken keeps the keys a place each
    @Test
    fun aKeyTypedInASyllableTakenKeepsTheKeysInStep() {
        val s = session(T9Segmenter(abbreviations = false), spell = true)
        s.type("94264")
        s.apply(Action.Syllable(0))
        s.apply(Action.Step.START)
        s.apply(Action.Step(1))
        s.type("4")
        assertTrue(s.pressedInStep)
        s.apply(Select(0))
        assertTrue(habits.last.isNotEmpty())
    }

    @Test
    fun aLetterThe连按ChoseIsFiledApart() {
        val s = session(T9Segmenter(abbreviations = false), spell = true)
        s.apply(Key('6'))
        s.apply(Action.Letter('n', again = true))
        s.type("4")
        s.apply(Select(0))
        assertEquals(Kinds.nineKeysMultiTap(NineKeyLetters.STANDARD.tag()) + ":n4", habits.last.single { it.whole }.code.toString())
    }

    @Test
    fun aGlideIsFiledAsFullPinyin() {
        val t9 = session(T9Segmenter(abbreviations = false), spell = true)
        for (c in "nihao") t9.apply(Action.Letter(c, glided = true))
        t9.apply(Select(0))
        assertEquals("qp:nihao", habits.last.single { it.whole }.code.toString())
        val sp = session(ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), spell = true, kind = Kinds.shuangpin("Ziranma"))
        for (c in "nihk") sp.apply(Key(c, glided = true))
        sp.apply(Select(0))
        assertEquals("qp:nihao", habits.last.single { it.whole }.code.toString())
    }

    @Test
    fun aGlidedWordTheSessionHoldsBackIsRecordedAsPicked() {
        val pinyin = session()
        val readings = listOf(GlideReading("nihao", "你好", 0f, "ni hao"), GlideReading("xian", "鲜", -1f, "xian"))
        val glide = GlideSession(pinyin, Key(SyllableGraph.SEPARATOR), heldBack = { r, first -> pinyin.heldBack(r.word, r.pinyin, first) }) { _, _ -> readings }
        glide.offer(Glide(floatArrayOf(0f, 0f), GlideLayout.QWERTY))
        val shown = glide.apply(Action.Glide)
        assertEquals("鲜", shown.candidates[1])
        assertEquals("鲜", glide.apply(Select(1)).commit)
        assertEquals(listOf("qp:xian||鲜(xian)|B*|3"), rows())
    }

    @Test
    fun pinsAndForgetsAreRecorded() {
        val s = session()
        s.type("nihao")
        s.apply(Action.Pin(0))
        assertEquals(1, habits.pins.size)
        assertTrue(habits.pins.single(), habits.pins.single().startsWith("qp:nihao ") && habits.pins.single().endsWith(" on"))
        s.apply(Action.Reset)
        s.type("ni")
        s.pick("拟")
        s.type("ni")
        s.apply(Action.Forget(s.candidates(0, 20).indexOfFirst { it.text == "拟" }))
        assertEquals(listOf("拟"), habits.forgotten)
    }

    // review-1a-core S4: a sentence forgotten is that text, not each word of it everywhere
    @Test
    fun aSentenceForgottenIsItsTextAlone() {
        val s = session()
        s.type("nihaoma")
        s.apply(Select(0))
        s.type("nihaoma")
        val sentence = s.candidates(0, 20).indexOfFirst { it.text == "你好吗" }
        s.apply(Action.Forget(sentence))
        assertEquals(listOf("你好吗"), habits.forgotten)
    }

    // review-1a-core open question: a key the session does not read (a comma) leaves no word before
    // what comes next, with no Context from the host
    @Test
    fun aKeyNotReadEndsTheWordBefore() {
        val s = session()
        s.type("nihao")
        assertEquals("你好", s.apply(Key(',')).commit.removeSuffix(","))
        s.type("zai")
        s.apply(Select(0))
        assertEquals("", habits.last.single { it.whole }.prev)
    }

    // review-1a-core open question: a phrase is no word picked (PLAN §2.4); the decoder's first after
    // it is the first shown, not a correction (NIT 10)
    @Test
    fun aPhrasePickedLearnsNothingAndTheWordAfterItIsFirst() {
        val s = session(phrases = PhraseBook(CustomPhrases.parse("nihao,1=你好呀")) {})
        s.type("nihao")
        assertEquals("你好呀", s.candidates(0, 1).single().text)
        s.apply(Select(0))
        assertTrue(habits.commits.isEmpty())
        s.type("nihao")
        s.apply(Select(1))
        assertEquals('A', habits.last.single { it.whole }.how)
    }

    // review-1a-app S1: a fault of the learning record's is reported, and the commit goes on
    @Test
    fun aFaultOfTheRecordsLosesNoCommit() {
        val failed = ArrayList<RuntimeException>()
        val broken = object : Habits by Habits.OFF {
            override val learns get() = true
            override fun record(rows: List<Row>, takes: List<Take>, flags: Int): LearnRecord.Commit? = error("a bug")
            override fun failed(e: RuntimeException) {
                failed += e
            }
        }
        val user = UserModel(data.dictionary, data.vocabulary)
        val s = PinyinSession(data, PinyinSegmenter(), prediction = false, user = user, learnTo = LearnTo(broken))
        s.type("nihao")
        assertEquals("你好", s.apply(Select(0)).commit)
        assertEquals(1, failed.size)
    }

    // review-1a-app S2: an unpin where nothing is learned is recorded as its pin was
    @Test
    fun anUnpinWhereNothingIsLearnedIsRecorded() {
        val s = session(phrases = PhraseBook(CustomPhrases.parse("")) {})
        s.type("nihao")
        val at = s.candidates(0, 20).indexOfFirst { it.text == "你好" }
        s.apply(Action.Pin(at))
        s.apply(Action.Reset)
        s.learning = false
        s.type("nihao")
        s.apply(Action.Unpin(s.candidates(0, 20).indexOfFirst { it.text == "你好" }))
        assertEquals(listOf("qp:nihao 你好 on", "qp:nihao 你好 off"), habits.pins)
    }

    @Test
    fun aPredictionPickedIsFiledUnderTheWordBefore() {
        val s = session(prediction = true)
        s.type("zai")
        val after = s.pick("再")
        assertEquals("见", after.candidates.first())
        s.apply(Select(0))
        // by its text, of no reading: 联想's alone (review-C S1)
        assertEquals(listOf("pred:再|再|见|D|3"), rows())
    }

    // review-1a-core S3: an address field's commits learn no pairs, a search's no 联想 (U-91)
    @Test
    fun aFieldsLearnFlagsGoWithItsCommits() {
        val s = session()
        s.apply(Action.Context("", learnFlags = LearnRecord.Commit.NO_PAIRS))
        s.type("ni")
        s.apply(Select(0))
        assertEquals(LearnRecord.Commit.NO_PAIRS, habits.flags.last())
        // the engine's own context (a glide's word held back, a lookup committed) leaves them (review-1a-r2 S1)
        s.apply(Action.Context("你"))
        s.type("ni")
        s.apply(Select(0))
        assertEquals(LearnRecord.Commit.NO_PAIRS, habits.flags.last())
        // the host's next, of another field, sets them
        s.apply(Action.Context("你", learnFlags = 0))
        s.type("ni")
        s.apply(Select(0))
        assertEquals(0, habits.flags.last())
    }

    // U-91: a 联想 pick in an address field learns no pair either (the reducer leaves pairs out by the flag)
    @Test
    fun aPredictionPickedGoesWithTheFieldsLearnFlags() {
        val s = session(prediction = true)
        s.apply(Action.Context("", learnFlags = LearnRecord.Commit.NO_PAIRS))
        s.type("zai")
        s.pick("再")
        s.apply(Select(0))
        assertEquals(listOf(LearnRecord.Commit.NO_PAIRS, LearnRecord.Commit.NO_PAIRS), habits.flags.takeLast(2))
        assertEquals("pred:再|再|见|D|3", rows().last())
    }

    @Test
    fun nothingIsRecordedWhileLearningIsOff() {
        val s = session()
        s.learning = false
        s.type("nihao")
        s.apply(Select(0))
        assertTrue(habits.commits.isEmpty())
    }

    @Test
    fun aStateBuiltLiveFromASessionReadsBack() {
        val log = LearnLog(null)
        val s = PinyinSession(data, PinyinSegmenter(), prediction = false, user = UserModel(data.dictionary, data.vocabulary), learnTo = LearnTo(LogHabits({ log })))
        repeat(2) {
            s.type("nizai")
            s.pick("拟")
            s.pick("再")
        }
        // committed twice, none's: the user's word
        assertTrue(log.state.mine.containsKey("拟再"))
    }
}
