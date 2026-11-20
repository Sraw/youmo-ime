/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.handwriting.handwritingData
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.learn.Words
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Action.Backspace
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Remember
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.session.Action.Unremember
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.OfferedWord
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.user.UserScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** 码表学词: a run typed a character at a time offers its words; one tapped is remembered under its rule code, undoably. */
class TableWordLearningTest {

    // 汪 ig, 小 ih, 明 je; 水暖 alone at iije, 尖 leads ihj on to ihje's 小明
    private val wubi = dictionary(
        "ig 汪", "iggg 汪", "ih 小", "ihty 小", "je 明", "jeee 明", "iije 水暖", "ihjx 尖",
        "sb 李", "sbbb 李", "aa 工", "aaaa 工", "ua 的", "uaaa 的", "g 高", "g 歌", "igih 汪小",
    )

    private val learner = Learner(handwritingData)
    private val kind = Kinds.table("wubi")
    private var clock = 0L

    private fun language(d: PinyinData = handwritingData, learner: Learner = this.learner): TableLanguage {
        val readings = Predictor(d.model, d.vocabulary, d.dictionary, learner.model)
        return TableLanguage(
            TextWords(d.model, d.wordIndex, UserScorer(d.model, learner.model)), learner.model,
            reading = { text -> d.wordIndex.find(text).takeIf { it != NO_WORD }?.let(readings::reading) },
            predictor = readings, habits = learner.habits, kind = kind,
        )
    }

    private val saved = wubi.savedIn(learner.log)

    private fun session(options: TableOptions = TableOptions.WUBI, language: TableLanguage? = language()) =
        TableSession(wubi, options, saved = saved, language = language, now = { clock })

    // what each session shows being typed: a space then picks, as Keyboard sends it
    private val preedits = HashMap<TableSession, String>()

    // each key a while after the last, sent as Keyboard sends a typed key
    private fun TableSession.keys(keys: String): Snapshot {
        var last: Snapshot? = null
        for (c in keys) {
            clock += 1000
            val composing = preedits[this].orEmpty().isNotEmpty()
            last = when {
                reads(c) -> apply(Key(c))
                composing && c == ' ' -> apply(Select(0))
                else -> apply(Key(c)).let { if (it.preedit.isEmpty()) it.copy(handled = false) else it }
            }
            preedits[this] = last.preedit
        }
        return last!!
    }

    private val wangXiaoming = "ig ih je "

    @Test
    fun aRunTypedACharacterAtATimeOffersItsWholeAndItsLastTwo() {
        val s = session().keys(wangXiaoming)
        assertEquals(listOf(OfferedWord("汪小明", "iije"), OfferedWord("小明", "ihje")), s.wordOffer?.words)
        // nothing learned till one is tapped
        assertTrue(learner.log.state.tableWords.isEmpty())
    }

    @Test
    fun whatEndsARunOrIsNoWordIsNotOffered() {
        class Row(val why: String, val keys: String, val offered: List<String>?, val options: TableOptions = TableOptions.WUBI)
        assertRows(
            listOf(
                Row("one character is no word", "ig ", null),
                Row("a mark ends the run: 明 committed by 顶字 at it is not offered", "ig ih je,", null),
                Row("a space the app typed (no candidate) ends it", "ig  ih je ", listOf("小明")),
                Row("a word the table has", "ig ih ", null),
                Row("a run starting with a function word offers its last two", "ua ih je ", listOf("小明")),
                Row("the same character over and over", "aa aa ", null),
                Row("its prefix leads nowhere longer: 李明 sbje would change what sb 顶字 does", "sb je ", null),
                Row("the setting off", wangXiaoming, null, TableOptions.WUBI.copy(offerWords = false)),
            ),
            { it.why },
        ) { row ->
            val s = session(row.options).keys(row.keys)
            assertEquals(row.offered, s.wordOffer?.words?.map { it.text })
        }
    }

    @Test
    @Uat("TBL-11f")
    fun aTableWithNoRulesOffersNone() {
        // as 仓颉's: no 组词规则 to give a word its code
        val cangjie = CodeTable.Builder().header("键码", "abcdefghijklmnopqrstuvwxyz").header("码长", "5")
            .entry("ig", "汪").entry("ih", "小").entry("je", "明").build().toByteArray()
            .let { TableDictionary(CodeTable.load(ByteBuffer.wrap(it))) }
        val t = TableSession(cangjie, TableOptions.CANGJIE, saved = cangjie.savedIn(learner.log), language = language(), now = { clock })
        assertNull(t.keys(wangXiaoming).wordOffer)
    }

    // a comma the app typed leaves the field's text unseen to the session: the text read as tapped is what is checked
    @Test
    fun afterAPassedOnKeyTheTextReadAsTappedDecides() {
        val t = session()
        t.keys("ig ,")
        val offer = t.keys("ih je ").wordOffer!!
        assertEquals(listOf("小明"), offer.words.map { it.text })
        assertEquals(0, t.apply(Remember(offer.id, 0, "汪，小")).deleteBefore)
        assertFalse(saved.isSaved("ihje", "小明"))
        val u = session()
        u.keys("ig ,")
        val again = u.keys("ih je ").wordOffer!!
        assertEquals(2, u.apply(Remember(again.id, 0, "汪，小明")).deleteBefore)
        assertTrue(saved.isSaved("ihje", "小明"))
    }

    @Test
    fun withNoLearningNothingIsOffered() {
        val t = session()
        t.learning = false
        assertNull(t.keys(wangXiaoming).wordOffer)
        assertNull(session(language = null).keys(wangXiaoming).wordOffer)
    }

    @Test
    fun anOfferLeftOnceSeenIsNotOfferedAgainInTheField() {
        val t = session()
        val offer = t.keys(wangXiaoming).wordOffer!!
        // shown by the app, then left: the next key withdraws it
        assertEquals(offer, t.apply(Action.OfferShown(offer.id)).wordOffer)
        t.keys(",")
        assertNull(t.keys(wangXiaoming).wordOffer?.words?.firstOrNull { it.text == "汪小明" })
        // another field: offered again
        t.apply(Action.Context("", started = true, said = true))
        assertEquals("汪小明", t.keys(wangXiaoming).wordOffer?.words?.first()?.text)
    }

    // one the app held back (another notice up, a finger down) was never seen: offered again
    @Test
    @Uat("TBL-11b")
    fun anOfferNeverShownIsOfferedAgain() {
        val t = session()
        val offer = t.keys(wangXiaoming).wordOffer!!
        // an older offer's id is no showing of this one
        t.apply(Action.OfferShown(offer.id - 1))
        t.keys(",")
        assertEquals("汪小明", t.keys(wangXiaoming).wordOffer?.words?.first()?.text)
    }

    // a tap is checked against the field's text: where it does not read, a tap could only do nothing
    @Test
    @Uat("TBL-11a")
    fun aFieldThatDoesNotReadIsOfferedNothing() {
        val t = session()
        t.apply(Action.Context("", started = true))
        assertNull(t.keys(wangXiaoming).wordOffer)
        t.apply(Action.Context("", started = true, said = true))
        assertEquals("汪小明", t.keys(wangXiaoming).wordOffer?.words?.first()?.text)
    }

    // a word the table has under a shorter code of its own (a 简码) is no new word
    @Test
    fun aWordTheTableHasUnderAShorterCodeIsNotOffered() {
        val shorter = dictionary("ig 汪", "ih 小", "je 明", "iij 汪小明", "iije 水暖", "ihjx 尖")
        val t = TableSession(shorter, TableOptions.WUBI, saved = shorter.savedIn(learner.log), language = language(), now = { clock })
        assertEquals(listOf("小明"), t.keys(wangXiaoming).wordOffer?.words?.map { it.text })
    }

    // 二笔's ; is a code key there: typed as one, it ends no run
    @Test
    @Uat("TBL-11f")
    fun erbisSemicolonInACodeEndsNoRun() {
        val erbi = CodeTable.Builder().header("键码", "abcdefghijklmnopqrstuvwxyz;").header("码长", "4")
            .rule("e2", "p11+p12+p21+p22").rule("e3", "p11+p21+p31+p32").rule("a4", "p11+p21+p31+n11")
            .entry("w;", "汪").entry("w;aa", "汪").entry("cx", "小").entry("cxaa", "小").entry("m;", "明").entry("m;aa", "明")
            .build().toByteArray().let { TableDictionary(CodeTable.load(ByteBuffer.wrap(it))) }
        val t = TableSession(erbi, TableOptions.ERBI, saved = erbi.savedIn(learner.log), language = language(), now = { clock })
        assertEquals(listOf(OfferedWord("汪小明", "wcm;"), OfferedWord("小明", "cxm;")), t.keys("w; cx m; ").wordOffer?.words)
    }

    @Test
    fun aDeletedCharacterRetypedGoesOnTheRun() {
        // 汪 小 工 ⌫ 明: the app's Backspace takes 工 off the run
        val t = session()
        t.keys("ig ih aa ")
        assertFalse(t.apply(Backspace).handled)
        assertEquals("汪小明", t.keys("je ").wordOffer?.words?.first()?.text)
    }

    @Test
    @Uat("TBL-11")
    fun aLoneCodeTakesTheRememberedWordAndNothingElse() {
        // pinyin's 水手 coded iije too: kept out of a lone code as ever, the remembered word or not
        val pinyins = object : SharedWords by SharedWords.NONE {
            override fun words(prefix: String) = if ("iije".startsWith(prefix)) listOf("iije" to "水手") else emptyList()
        }
        val t = TableSession(wubi, TableOptions.WUBI, saved = saved, shared = pinyins, language = language(), now = { clock })
        assertEquals("水暖", t.keys("iije").commit)
        t.apply(Action.Reset)
        t.apply(Remember(t.keys(wangXiaoming).wordOffer!!.id, 0, "汪小明"))
        t.keys(",")
        val typed = t.keys("iije")
        assertEquals("", typed.commit)
        assertEquals(listOf("水暖", "汪小明"), typed.candidates)
    }

    @Test
    fun tappedTheWordReplacesItsCharactersAndIsTypedByItsCode() {
        val t = session()
        val offer = t.keys(wangXiaoming).wordOffer!!
        val s = t.apply(Remember(offer.id, 0, "她叫汪小明"))
        assertEquals("汪小明", s.commit)
        assertEquals(3, s.deleteBefore)
        assertEquals(OfferedWord("汪小明", "iije"), s.remembered)
        assertEquals(setOf(Triple("engine-wubi", "iije", "汪小明")), learner.log.state.tableWords.keys)
        // a 重码 now: shown, not committed by itself; the table's first
        t.keys(",")
        val typed = t.keys("iije")
        assertEquals("", typed.commit)
        assertEquals(listOf("水暖", "汪小明"), typed.candidates)
        // its prefix as before
        t.apply(Action.Reset)
        assertEquals(listOf("尖"), t.keys("ihj").candidates)
    }

    @Test
    fun aTapNoLongerMatchingTheFieldDoesNothing() {
        class Row(val why: String, val act: (TableSession, Int) -> Snapshot)
        assertRows(
            listOf(
                Row("an older offer's id") { t, id -> t.apply(Remember(id + 1, 0, "汪小明")) },
                Row("the field no longer ends with the word") { t, id -> t.apply(Remember(id, 0, "汪小")) },
                Row("no such word") { t, id -> t.apply(Remember(id, 5, "汪小明")) },
            ),
            { it.why },
        ) { row ->
            learner.log.state.tableWords.keys.toList().forEach { (_, code, text) -> saved.forget(code, text) }
            val t = session()
            val id = t.keys(wangXiaoming).wordOffer!!.id
            assertEquals("", row.act(t, id).commit)
            assertTrue(learner.log.state.tableWords.isEmpty())
        }
    }

    @Test
    fun deletedWholeRightAfterItIsForgottenAndPartOfItIsKept() {
        val t = session()
        t.apply(Remember(t.keys(wangXiaoming).wordOffer!!.id, 0, "汪小明"))
        assertFalse(t.apply(Backspace).handled)
        t.keys(",")
        assertTrue(saved.isSaved("iije", "汪小明"))
        saved.forget("iije", "汪小明")
        val u = session()
        u.apply(Remember(u.keys(wangXiaoming).wordOffer!!.id, 0, "汪小明"))
        repeat(3) { assertFalse(u.apply(Backspace).handled) }
        u.apply(Action.Refine)
        assertFalse(saved.isSaved("iije", "汪小明"))
    }

    @Test
    fun theNoticesUndoTakesItBackWithItsPickAndTheTextStays() {
        val t = session()
        t.apply(Remember(t.keys(wangXiaoming).wordOffer!!.id, 0, "汪小明"))
        assertTrue(learner.habits.uses(Code(kind, "iije"), Words.of("汪小明")) > 0)
        val s = t.apply(Unremember("汪小明", "iije"))
        assertEquals("", s.commit)
        assertFalse(saved.isSaved("iije", "汪小明"))
        assertEquals(0, learner.habits.uses(Code(kind, "iije"), Words.of("汪小明")))
        // unique again: commits itself
        t.keys(",")
        assertEquals("水暖", t.keys("iije").commit)
    }

    // an input method switched and back makes the session anew: the notice's 撤销 still takes back the commit's pick
    @Test
    fun theNoticesUndoReachesASessionMadeAnew() {
        val t = session()
        t.apply(Remember(t.keys(wangXiaoming).wordOffer!!.id, 0, "汪小明"))
        session().apply(Unremember("汪小明", "iije"))
        assertFalse(saved.isSaved("iije", "汪小明"))
        assertEquals(0, learner.habits.uses(Code(kind, "iije"), Words.of("汪小明")))
    }

    // the space after a tapped prediction's next round is a space, as after any round just come
    @Test
    @Uat("PY-160")
    fun aSpaceJustAfterATappedPredictionsNextRoundIsASpace() {
        // 高 山 水: 山 tapped brings 水
        val chain = PinyinDataBuilder().unigram("<unk>", -7f, 0f).unigram("高", -3f, -1f).unigram("山", -3f, -1f).unigram("水", -3f, 0f)
            .bigram("高", "山", -0.2f, 0f).bigram("山", "水", -0.2f, 0f)
            .entry("高", intArrayOf(Syllables.id("gao"))).entry("山", intArrayOf(Syllables.id("shan"))).entry("水", intArrayOf(Syllables.id("shui")))
            .build().toByteArray().let { PinyinData.load(ByteBuffer.wrap(it)) }
        val t = TableSession(
            wubi, TableOptions.WUBI.copy(spacePicksPrediction = true, orderByUse = false), saved = saved,
            language = language(chain, Learner(chain)), now = { clock },
        )
        val first = t.keys("g ").candidates
        assertEquals("山", first.first())
        clock += 1000
        val next = t.apply(Select(0))
        assertEquals("山", next.commit)
        assertTrue(next.toString(), next.predicting && next.candidates != first)
        clock += 100
        assertFalse(t.apply(Key(' ')).handled)
    }

    @Test
    fun remembered2ndUnderItsCodeAndFirstOncePickedThereWithOrderByUse() {
        val t = session()
        t.apply(Remember(t.keys(wangXiaoming).wordOffer!!.id, 0, "汪小明"))
        t.keys(",")
        assertEquals(listOf("水暖", "汪小明"), t.keys("iije").candidates)
        assertEquals("汪小明", t.apply(Select(1)).commit)
        assertEquals(listOf("汪小明", "水暖"), t.keys("iije").candidates)
    }

    @Test
    fun withOrderByUseOffTheTablesOwnStaysFirst() {
        val t = session(TableOptions.WUBI.copy(orderByUse = false))
        t.apply(Remember(t.keys(wangXiaoming).wordOffer!!.id, 0, "汪小明"))
        t.keys(",")
        assertEquals(listOf("水暖", "汪小明"), t.keys("iije").candidates)
        t.apply(Select(1))
        assertEquals(listOf("水暖", "汪小明"), t.keys("iije").candidates)
        // 为什么排在这 names it remembered, with its uses there (the tap that remembered it one); the table's own word is not
        assertRows(
            listOf(Triple(1, true, 2), Triple(0, false, 0)),
            { (at, remembered) -> "${t.candidates(at, 1).single().text}: remembered=$remembered" },
        ) { (at, remembered, uses) -> assertEquals(remembered to uses, t.why(at)!!.let { it.remembered to it.uses }) }
    }

    @Test
    fun deletedByALongPressItGoesFromItsCodeAlone() {
        val t = session()
        t.apply(Remember(t.keys(wangXiaoming).wordOffer!!.id, 0, "汪小明"))
        t.keys(",")
        t.keys("iije")
        assertEquals(setOf(Offer.FORGET, Offer.PIN, Offer.BLOCK), t.offers(1))
        assertEquals(listOf("水暖"), t.apply(Action.Forget(1)).candidates)
        assertTrue(learner.log.state.tableWords.isEmpty())
    }

    @Test
    @Uat("PY-160")
    fun aSpaceTakesThePredictionShownOnlyWithTheSwitchOn() {
        class Row(val why: String, val on: Boolean, val wait: Long, val commit: String)
        assertRows(
            listOf(
                Row("off: a space as ever", on = false, wait = 1000, commit = " "),
                Row("on: the first prediction", on = true, wait = 1000, commit = "PREDICTION"),
                Row("on, but the predictions came just now: a space", on = true, wait = 100, commit = " "),
            ),
            { it.why },
        ) { row ->
            val t = session(TableOptions.WUBI.copy(spacePicksPrediction = row.on, orderByUse = false))
            // 高 picked by a space (no self-commit, whose next space is swallowed): the model's 山 may follow
            val shown = t.keys("g ")
            assertEquals("高", shown.commit)
            assertTrue(shown.predicting)
            val first = shown.candidates.first()
            assertEquals("山", first)
            assertEquals(if (row.on) 0 else -1, shown.spacePicks)
            assertEquals(row.on, shown.spaceMarks)
            clock += row.wait
            val s = t.apply(Key(' '))
            if (row.commit == " ") {
                assertFalse(s.handled)
            } else {
                assertEquals(first, s.commit)
                // a space after the one that took it is a space
                clock += 1000
                assertFalse(t.apply(Key(' ')).handled)
            }
        }
    }

    @Test
    @Uat("PY-160")
    fun noSpaceAfterASelfCommitTakesAPrediction() {
        // 水暖 alone at iije commits itself: the space next is swallowed within the second, a space after it
        val t = session(TableOptions.WUBI.copy(spacePicksPrediction = true))
        val committed = t.keys("iije")
        assertEquals("水暖", committed.commit)
        // no prediction marked: the bar shows this space is no pick
        if (committed.predicting) assertEquals(-1 to true, committed.spacePicks to committed.spaceMarks)
        clock += 100
        assertTrue(t.apply(Key(' ')).let { it.handled && it.commit.isEmpty() })
        clock += 2000
        assertFalse(t.apply(Key(' ')).handled)
    }

    @Test
    @Uat("TBL-47a")
    fun aCapitalLetterGoesInAsItIsAndEndsTheRun() {
        // ⇧ on a table's letters: typing a code, its first candidate goes in before the letter
        class Row(val why: String, val keys: String, val commit: String)
        assertRows(
            listOf(
                Row("nothing typed: the app's letter", "A", ""),
                Row("typing ih: 小 first, then the letter", "ihA", "小"),
            ),
            { it.why },
        ) { row ->
            val s = session().keys(row.keys)
            assertEquals(row.commit, s.commit)
            assertFalse(s.handled)
        }
        assertNull(session().keys("ig ih A je ").wordOffer?.words?.firstOrNull { it.text == "汪小明" })
    }
}
