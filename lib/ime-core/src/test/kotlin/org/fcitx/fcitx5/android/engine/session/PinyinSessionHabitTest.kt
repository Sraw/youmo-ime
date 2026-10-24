/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.glide.PinyinGlide
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Habits
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnTo
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.learn.RecordingHabits
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.Take
import org.fcitx.fcitx5.android.engine.learn.row
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.Action.CommitRaw
import org.fcitx.fcitx5.android.engine.session.Action.Forget
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Pin
import org.fcitx.fcitx5.android.engine.session.Action.Reset
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.session.Action.Unpin
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pinyin session ranked by the user's habits (impl/design-1b.md §2, §6): what follows the first, pins, forgetting, expiry. */
class PinyinSessionHabitTest {

    private val data = sessionTestData()
    private val learner = Learner(data)

    private fun session(refiner: SentenceRefiner? = null) =
        PinyinSession(data, PinyinSegmenter(), pageSize = 20, prediction = false, user = learner.model, learnTo = learner.learnTo(), refiner = refiner)

    private val session = session()

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    private fun Session.pick(keys: String, text: String) {
        val shown = type(keys).candidates
        assertTrue("$text in $shown", text in shown)
        apply(Select(shown.indexOf(text)))
        apply(Reset)
    }

    // xian: 西安 corrected to twice is first, and the preedit and Enter read the input as it does
    @Test
    fun thePreeditAndEnterFollowTheRankedFirst() {
        val fresh = session.type("xian")
        assertEquals("先", fresh.candidates.first())
        assertEquals("xian", fresh.preedit)
        session.apply(Reset)
        repeat(2) { session.pick("xian", "西安") }
        val ranked = session.type("xian")
        assertEquals("西安", ranked.candidates.first())
        assertEquals("xi an", ranked.preedit)
        assertEquals("xian", session.apply(CommitRaw).commit)
    }

    // a pause's refining weighs the readings again, never over the user's first (rule 1)
    @Test
    fun refiningKeepsTheUsersFirst() {
        repeat(2) { session.pick("xian", "西安") }
        val refining = session { _, readings, _, _ -> readings.indexOfFirst { it.startsWith("先") } }
        refining.type("xian")
        assertEquals("西安", refining.apply(Action.Refine).candidates.first())
    }

    // review F3092/F3102 (F2739): 西安 corrected to once, then shown first by a pin; 先, the decoder's
    // own first, picked over it is a use of it: the streak restarts, so 西安 needs two more picks
    @Test
    fun theDecodersOwnFirstPickedOverAnotherShownFirstRestartsTheStreak() {
        val recorded = ArrayList<Row>()
        val habits = object : Habits by learner.habits {
            override fun record(rows: List<Row>, takes: List<Take>, flags: Int) = learner.habits.record(rows, takes, flags).also { recorded += rows }
        }
        val s = PinyinSession(data, PinyinSegmenter(), pageSize = 20, prediction = false, user = learner.model, learnTo = LearnTo(habits))
        fun pick(text: String): Row {
            recorded.clear()
            s.pick("xian", text)
            return recorded.first()
        }
        assertTrue(pick("西安").corrected)
        val shown = s.type("xian").candidates
        assertEquals("西安", s.apply(Pin(shown.indexOf("西安"))).candidates.first())
        s.apply(Reset)
        val own = pick("先")
        assertTrue(own.corrected && own.ownFirst)
        s.type("xian")
        s.apply(Unpin(0))
        s.apply(Reset)
        val again = pick("西安")
        assertTrue(again.corrected && !again.ownFirst)
        assertEquals("先", s.type("xian").candidates.first())
        s.apply(Reset)
        pick("西安")
        assertEquals("西安", s.type("xian").candidates.first())
    }

    @Test
    fun aFirstForgottenIsTheDecodersAgain() {
        repeat(2) { session.pick("xian", "西安") }
        val shown = session.type("xian").candidates
        assertEquals("西安", shown.first())
        session.apply(Forget(0))
        session.apply(Reset)
        assertEquals("先", session.type("xian").candidates.first())
    }

    // U-07: a first unused for 30 typing days is gone; days with no typing are none
    @Test
    fun aFirstUnusedForThirtyTypingDaysIsGone() {
        repeat(2) { session.pick("xian", "西安") }
        learner.clock.days(60)
        repeat(29) {
            learner.clock.days(1)
            learner.habits.record(listOf(row("zai", "在", "zai")))
        }
        assertEquals("西安", session.type("xian").candidates.first())
        session.apply(Reset)
        learner.clock.days(1)
        learner.habits.record(listOf(row("zai", "在", "zai")))
        assertEquals("先", session.type("xian").candidates.first())
    }

    // S8: a word of level 2 (ending where the decoder's first word ends) is pinned under the keys up to there
    @Test
    fun aWordOfLevelTwoIsPinnedUnderItsOwnKeys() {
        val habits = RecordingHabits()
        val s = PinyinSession(data, PinyinSegmenter(), pageSize = 20, prediction = false, learnTo = LearnTo(habits))
        val shown = s.type("nihaoma").candidates
        val at = shown.indexOf("你好")
        assertTrue("你好 in $shown", at > 0)
        s.apply(Pin(at))
        assertEquals(listOf("qp:nihao 你好 on"), habits.pins)
        // and goes in front there
        s.apply(Reset)
        assertEquals("你好", s.type("nihao").candidates.first())
    }

    // design-1b M2: the second of two picks is ranked after the word the first ends on, as it is filed
    @Test
    fun theRestAfterAPieceIsRankedAfterIt() {
        fun pieces(): Snapshot {
            session.apply(Action.Context("你"))
            val shown = session.type("haozai").candidates
            return session.apply(Select(shown.indexOf("好")))
        }
        assertEquals("在", pieces().candidates.first())
        repeat(2) {
            session.apply(Select(pieces().candidates.indexOf("再")))
            session.apply(Reset)
        }
        assertEquals("再", pieces().candidates.first())
    }

    // design-1b §6 (b): 点 then 了 picked under dl is 点了 there next time, walked along the input, never made
    @Test
    fun twoPicksAreOneTextUnderTheirKeysNextTime() {
        val dl = pinyinDataOf(Triple("带来", "dai lai", -2f), Triple("点", "dian", -3f), Triple("打", "da", -3f), Triple("了", "le", -2f), Triple("来", "lai", -3f))
        val learner = Learner(dl)
        val s = PinyinSession(dl, PinyinSegmenter(), pageSize = 20, prediction = false, user = learner.model, learnTo = learner.learnTo())
        val shown = s.type("dl").candidates
        assertEquals("带来", shown.first())
        val rest = s.apply(Select(shown.indexOf("点"))).candidates
        assertEquals("点了", s.apply(Select(rest.indexOf("了"))).commit)
        s.apply(Reset)
        // used there, after the decoder's first; corrected to twice, first
        val once = s.type("dl").candidates
        assertEquals(listOf("带来", "点了"), once.take(2))
        s.apply(Select(1))
        s.apply(Reset)
        assertEquals("点了", s.type("dl").candidates.first())
    }

    // U-20 (D32 B), R14 F3734: 点了, no word of the dictionary, corrected to once under dl is walked in
    // under dianl, no lower than second; under dl itself it goes by U-02
    @Test
    fun aWordCorrectedToUnderItsInitialsIsWalkedInSpeltInPart() {
        val dl = pinyinDataOf(
            Triple("带来", "dai lai", -2f), Triple("电量", "dian liang", -2f), Triple("店里", "dian li", -2f), Triple("电力", "dian li", -2.1f), Triple("点", "dian", -3f), Triple("打", "da", -3f),
            Triple("了", "le", -4f), Triple("来", "lai", -3f), Triple("量", "liang", -3f), Triple("里", "li", -2f),
        )
        val learner = Learner(dl)
        val s = PinyinSession(dl, PinyinSegmenter(), pageSize = 20, prediction = false, user = learner.model, learnTo = learner.learnTo())
        val fresh = s.type("dianl").candidates.indexOf("点了")
        assertTrue("$fresh", fresh < 0 || fresh > 1)
        s.apply(Reset)
        val shown = s.type("dl").candidates
        val rest = s.apply(Select(shown.indexOf("点"))).candidates
        assertEquals("点了", s.apply(Select(rest.indexOf("了"))).commit)
        s.apply(Reset)
        assertEquals(1, s.type("dianl").candidates.indexOf("点了"))
    }

    // U-20 (D32 B): 奉化 used twice spelt in full goes third under its 简拼 and its parts (fengh, feng'h:
    // 半截 as the user asked); once is not enough, and never first nor second so
    @Test
    fun aWordUsedTwiceSpeltInFullGoesThirdUnderItsInitialsAndParts() {
        val fh = pinyinDataOf(
            Triple("风化", "feng hua", -1.5f), Triple("丰厚", "feng hou", -1.6f), Triple("风火", "feng huo", -1.7f),
            Triple("凤凰", "feng huang", -1.8f), Triple("发挥", "fa hui", -1.4f), Triple("奉化", "feng hua", -4f),
            Triple("风", "feng", -2f), Triple("化", "hua", -2f), Triple("奉", "feng", -4f),
        )
        val learner = Learner(fh)
        val s = PinyinSession(fh, PinyinSegmenter(), pageSize = 20, prediction = false, user = learner.model, learnTo = learner.learnTo())
        fun at(keys: String) = s.type(keys).candidates.indexOf("奉化").also { s.apply(Reset) }
        val fresh = listOf("fh", "fengh", "feng'h").associateWith(::at)
        assertTrue("$fresh", fresh.values.all { it > 2 })
        fun use() {
            val shown = s.type("fenghua").candidates
            s.apply(Select(shown.indexOf("奉化")))
            s.apply(Reset)
        }
        use()
        assertEquals(fresh, fresh.keys.associateWith(::at))
        use()
        for (keys in fresh.keys) assertEquals(keys, 2, at(keys))
    }

    // review-1b S3: where nothing is learned (a password) the pin is still first, nothing recorded
    @Test
    fun aPinIsFirstWhereNothingIsLearned() {
        learner.habits.pin(Code(Kinds.FULL, "zai"), "再", "zai", on = true)
        val before = learner.log.state.stats.size
        session.learning = false
        val shown = session.type("zai").candidates
        assertEquals("再", shown.first())
        session.apply(Select(1))
        assertEquals(before, learner.log.state.stats.size)
    }

    // M3: a word of the user's own unused for 31 typing days keeps its reading for 联想 and glide
    @Test
    fun aWordOfTheirOwnUnusedForAMonthKeepsItsReading() {
        repeat(2) {
            val shown = session.type("nizai").candidates
            session.apply(Select(shown.indexOf("拟")))
            session.apply(Select(0))
            session.apply(Reset)
        }
        learner.clock.days(1)
        repeat(31) {
            learner.clock.days(1)
            learner.habits.record(listOf(row("zai", "在", "zai")))
        }
        val model = learner.model
        val id = model.known(UserModel.Entry("拟再", intArrayOf(Syllables.id("ni"), Syllables.id("zai"))))
        assertEquals(0f, model.count(id), 0f)
        // 联想's reading of it (Predictor: UserModel.chosen) and glide's (the user's trie)
        assertTrue(model.chosen(id))
        val glide = PinyinGlide(data, user = { PinyinGlide.User(model to model.changes, null, model, null, model::blocked) })
        val path = "nizai".flatMap { GlideLayout.QWERTY.centre(it).toList() }.toFloatArray()
        assertTrue(glide.decode(path, GlideLayout.QWERTY, 10).any { it.word == "拟再" })
        // and under 简拼, after the model's first
        assertEquals("拟再", session.type("nz").candidates[1])
    }

    // review-1b M1: a name added by hand, none of the model's words, is put in under its 简拼 and in full,
    // past the decoder's first words: right after those used there
    @Test
    fun aNameAddedByHandIsOnTheFirstPage() {
        val names = names()
        val model = UserModel(names.dictionary, names.vocabulary, index = names.wordIndex)
        model.listByHand(UserModel.Entry("汪小明", intArrayOf(Syllables.id("wang"), Syllables.id("xiao"), Syllables.id("ming"))))
        val learner = Learner(names)
        fun session() = PinyinSession(
            names, PinyinSegmenter(), pageSize = 5, prediction = false, user = model, learnTo = learner.learnTo(), addedWords = { setOf("汪小明") },
        )
        val short = session().type("wxm").candidates
        assertEquals("wxm: $short", listOf("王小明", "汪小明"), short.take(2))
        val full = session().type("wangxiaoming").candidates
        assertTrue("wangxiaoming: $full", full.indexOf("汪小明") in 0..1)
        // level 2 (S2): the decoder's first word 王小明, the name put in right after it, in that level's places
        val wide = PinyinSession(names, PinyinSegmenter(), pageSize = 20, prediction = false, user = model, learnTo = learner.learnTo(), addedWords = { setOf("汪小明") })
        val longer = wide.type("wxmxm").candidates
        assertTrue(longer.toString(), "王小明" in longer)
        assertEquals(longer.toString(), longer.indexOf("王小明") + 1, longer.indexOf("汪小明"))
    }

    // review-1b N3: letters committed as typed are learned (U-27), so they are the user's to forget (U-82)
    @Test
    fun lettersLearnedCanBeForgotten() {
        val fresh = session.type("vvv").candidates
        assertTrue(fresh.toString(), "vvv" in fresh)
        assertTrue(Offer.FORGET !in session.offers(fresh.indexOf("vvv")))
        session.apply(CommitRaw)
        session.apply(Reset)
        val learned = session.type("vvv").candidates
        assertTrue(Offer.FORGET in session.offers(learned.indexOf("vvv")))
    }

    // review-1b S2: a word added by hand deleted from the keyboard is the dictionary's again at once, the sessions kept
    @Test
    fun aWordAddedByHandDeletedIsTheirsNoMore() {
        val added = mutableSetOf("西安")
        val entry = UserModel.Entry("西安", intArrayOf(Syllables.id("xi"), Syllables.id("an")))
        learner.model.listByHand(entry)
        val s = PinyinSession(data, PinyinSegmenter(), pageSize = 20, prediction = false, user = learner.model, learnTo = learner.learnTo(), addedWords = { added })
        val shown = s.type("xian").candidates
        assertTrue(Offer.FORGET in s.offers(shown.indexOf("西安")))
        s.apply(Reset)
        added -= "西安"
        learner.model.unlist(entry)
        val again = s.type("xian").candidates
        assertTrue(Offer.FORGET !in s.offers(again.indexOf("西安")))
    }

    // review-1b S4: a sentence pinned, no word of the model's, is put in as its words, whatever the decoder's beam kept
    @Test
    fun aSentencePinnedIsFirstThoughTheDecoderMissesIt() {
        val names = names()
        val learner = Learner(names)
        val s = PinyinSession(names, PinyinSegmenter(), pageSize = 5, prediction = false, user = learner.model, learnTo = learner.learnTo())
        val fresh = s.type("wxmwxm").candidates
        assertTrue(fresh.toString(), "丁小明丁小明" !in fresh)
        s.apply(Reset)
        learner.habits.pin(Code(Kinds.FULL, "wxmwxm"), "丁小明丁小明", "wai xiao ming wai xiao ming", on = true)
        assertEquals("丁小明丁小明", s.type("wxmwxm").candidates.first())
    }

    // more names of wxm than the decoder lists words of all the input (PinyinDecoder.DEFAULT_WORDS), each
    // likelier than a word the model has not; 王小明 likelier still
    private fun names(): PinyinData {
        val w = listOf("wa", "wai", "wan", "wang", "wei", "wen", "weng", "wo", "wu")
        val names = (0 until 80).map { Triple(String(Character.toChars(0x4E00 + it)) + "小明", w[it % w.size] + " xiao ming", -3f) }
        return pinyinDataOf(*(names + listOf(Triple("小", "xiao", -1f), Triple("明", "ming", -1f), Triple("王小明", "wang xiao ming", -1.5f))).toTypedArray())
    }
}
