/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.handwriting.handwritingData
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.HabitRanker.Tier
import org.fcitx.fcitx5.android.engine.learn.Habits
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.Take
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Action.Block
import org.fcitx.fcitx5.android.engine.session.Action.Context
import org.fcitx.fcitx5.android.engine.session.Action.Pin
import org.fcitx.fcitx5.android.engine.session.Action.Reset
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.session.Action.Unpin
import org.fcitx.fcitx5.android.engine.session.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a table learns (U-28): rows under the code typed, pins and blocks there alone, never pinyin's order. */
class TableHabitsTest {

    private val wubi = dictionary("a 工", "a 戈", "aa 式", "aaaa 工", "aaaa 恭恭敬敬", "aaad 工期", "wqiy 你", "wun 们", "kkkk 叫", "kkkk 员", "g 高")

    private val learner = Learner(handwritingData)

    // what the session writes, written on into the learner's record
    private val recorded = ArrayList<Row>()
    private val habits = object : Habits by learner.habits {
        override fun record(rows: List<Row>, takes: List<Take>, flags: Int) = learner.habits.record(rows, takes, flags).also { recorded += rows }
    }

    private val kind = Kinds.table("wubi")

    private fun session(options: TableOptions = TableOptions.WUBI.copy(autoSelect = false), shared: SharedWords = SharedWords.NONE) =
        TableSession(wubi, options, shared = shared, language = learning(learner, habits))

    // review R11 F2741 (G4a): a field cleared since leaves no word before the next pick
    @Test
    fun aClearedFieldLeavesNoWordBeforeThePick() {
        val t = session()
        t.apply(Context(before = "你好", cleared = true))
        recorded.clear()
        t.type("aaaa")
        t.apply(Select(1))
        assertTrue(recorded.isNotEmpty())
        assertTrue(recorded.all { it.prev == "" })
    }

    // F2739 (TBL-39): 员 corrected to once, then shown first (here by a pin; 调频's models do it on a
    // phone); 叫, the table's own first, picked over it is a use of it: the streak restarts, so 员 needs
    // two more picks to be first
    @Test
    fun theTablesOwnFirstPickedOverAnotherShownFirstRestartsTheStreak() {
        val t = session(TableOptions.WUBI.copy(autoSelect = false, orderByUse = true))
        val code = Code(kind, "kkkk")
        fun pick(text: String): Row {
            val shown = t.type("kkkk").second.candidates
            recorded.clear()
            t.apply(Select(shown.indexOf(text)))
            return recorded.first()
        }
        assertTrue(pick("员").corrected)
        t.type("kkkk")
        assertEquals(listOf("员", "叫"), t.apply(Pin(1)).candidates)
        t.apply(Reset)
        val own = pick("叫")
        assertTrue(own.corrected && own.ownFirst)
        t.type("kkkk")
        t.apply(Unpin(0))
        t.apply(Reset)
        val again = pick("员")
        assertTrue(again.corrected && !again.ownFirst)
        assertNull(learner.habits.first(code, ""))
        pick("员")
        assertEquals("员", learner.habits.first(code, "")?.text)
    }

    // TBL-39: the own first picked while a pin shows another first leaves, once unpinned, the own first first
    @Test
    fun theOwnFirstPickedUnderAPinIsStillFirstOnceUnpinned() {
        val t = session(TableOptions.WUBI.copy(autoSelect = false, orderByUse = true))
        fun pick(text: String) {
            val shown = t.type("kkkk").second.candidates
            t.apply(Select(shown.indexOf(text)))
        }
        pick("员")
        t.type("kkkk")
        t.apply(Pin(1))
        t.apply(Reset)
        pick("叫")
        t.type("kkkk")
        assertEquals(listOf("叫", "员"), t.apply(Unpin(0)).candidates.take(2))
        t.apply(Reset)
        assertEquals(listOf("叫", "员"), t.type("kkkk").second.candidates.take(2))
        t.apply(Reset)
        pick("员")
        assertEquals(listOf("叫", "员"), t.type("kkkk").second.candidates.take(2))
        t.apply(Reset)
    }

    @Test
    fun aPickIsLearnedUnderTheCodeTypedNoWordBeforeItTheWholeAfterIt() {
        val t = session()
        t.type("g")
        assertEquals("高", t.apply(Select(0)).commit)
        recorded.clear()
        t.type("aaaa")
        assertEquals("恭恭敬敬", t.apply(Select(1)).commit)
        assertEquals(2, recorded.size)
        val (pick, whole) = recorded
        assertEquals(Code(kind, "aaaa"), pick.code)
        assertEquals("", pick.prev)
        // picked over the first shown: corrected
        assertEquals(Row.PICK or Row.CORRECTED, pick.flags)
        assertEquals("工", pick.shown)
        assertEquals('B', pick.how)
        assertEquals(Code(kind, ""), whole.code)
        assertEquals("高", whole.prev)
        assertTrue(whole.whole)
        // its words have no reading: never pinyin's order (D28)
        assertEquals("", whole.words.list.single().reading)
        assertEquals(0f, learner.model.count(handwritingData.wordIndex.find("高")), 0f)
        // the whole commit, under no keys, has its words' uses and pairs alone: no STAT of its own (review C S8)
        val state = learner.log.state
        assertTrue(state.stats.keys.none { it.code == Code(kind, "") })
        assertTrue("恭恭敬敬" in state.wordUses)
    }

    @Test
    fun nothingIsLearnedNorOfferedWhereLearningIsOff() {
        val t = session()
        t.learning = false
        t.type("aaaa")
        t.apply(Select(1))
        assertTrue(recorded.isEmpty())
        assertEquals(emptySet<Offer>(), t.offers(0))
    }

    // review R11 F2683: learning off (a password field), a long press changes nothing learned
    @Test
    fun aPinBlockOrForgetChangesNothingWhereLearningIsOff() {
        val t = session()
        t.type("aaaa")
        t.apply(Pin(1))
        t.apply(Reset)
        t.type("kkkk")
        t.apply(Block(1))
        t.apply(Reset)
        recorded.clear()
        t.learning = false
        t.type("aaaa")
        for (action in listOf(Unpin(0), Block(0), Block(1), Pin(1), Action.Forget(0), Action.Forget(1))) t.apply(action)
        t.apply(Reset)
        t.type("kkkk")
        for (action in listOf(Pin(0), Block(0), Action.Forget(0))) t.apply(action)
        t.apply(Reset)
        assertEquals("恭恭敬敬", learner.habits.pinned(Code(kind, "aaaa"))?.text)
        assertTrue(learner.habits.blockedUnder(Code(kind, "aaaa")).isEmpty())
        assertNull(learner.habits.pinned(Code(kind, "kkkk")))
        assertEquals(setOf("员"), learner.habits.blockedUnder(Code(kind, "kkkk")))
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun aPinPutsACandidateFirstUnderTheCodeTypedOrderedByUseOrNot() {
        for (orderByUse in listOf(true, false)) {
            val t = session(TableOptions.WUBI.copy(autoSelect = false, orderByUse = orderByUse))
            assertEquals(listOf("工", "恭恭敬敬"), t.type("aaaa").second.candidates)
            assertEquals(setOf(Offer.PIN, Offer.BLOCK), t.offers(1))
            assertEquals(listOf("恭恭敬敬", "工"), t.apply(Pin(1)).candidates)
            assertEquals(setOf(Offer.UNPIN, Offer.BLOCK), t.offers(0))
            t.apply(Reset)
            // there alone: aaa leads to it as before
            assertEquals("工", t.type("aaa").second.candidates.first())
            t.apply(Reset)
            assertEquals("恭恭敬敬", t.type("aaaa").second.candidates.first())
            assertEquals(listOf("工", "恭恭敬敬"), t.apply(Unpin(0)).candidates)
            t.apply(Reset)
        }
    }

    @Test
    fun aBlockHidesACandidateUnderTheCodeTypedAloneACharacterToo() {
        val t = session()
        t.type("aaaa")
        t.apply(Pin(1))
        // pinned, then blocked: the block wins, the pin goes
        assertEquals(listOf("工"), t.apply(Block(0)).candidates)
        assertEquals(null, learner.habits.pinned(Code(kind, "aaaa")))
        t.apply(Reset)
        assertTrue("恭恭敬敬" in t.type("aaa").second.candidates)
        t.apply(Reset)
        assertEquals(setOf("恭恭敬敬"), learner.habits.blockedUnder(Code(kind, "aaaa")))
        // a character of the table's own, its first: blocked under a alone
        t.type("a")
        assertEquals(listOf("戈", "式"), t.apply(Block(0)).candidates.take(2))
        t.apply(Reset)
        assertTrue("工" in t.type("aa").second.candidates)
    }

    @Test
    fun aBlockUnderACodeIsLiftedBackAndOutlivesTheTablesOrderReset() {
        val t = session()
        t.type("aaaa")
        t.apply(Block(1))
        t.apply(Reset)
        t.type("aaad")
        t.apply(Pin(0))
        t.apply(Reset)
        habits.resetTableOrder(Kinds.tableOf(kind))
        assertEquals(listOf("工"), t.type("aaaa").second.candidates)
        t.apply(Reset)
        assertEquals("工期", t.type("aaad").second.candidates.first())
        t.apply(Reset)
        habits.blockUnder(Code(kind, "aaaa"), "恭恭敬敬", on = false)
        assertEquals(listOf("工", "恭恭敬敬"), t.type("aaaa").second.candidates)
    }

    @Test
    fun aBlockElsewhereHidesNoneOfTheTablesOwnOnlyTheWordsFromOutsideIt() {
        // 工 and 恭工 blocked in pinyin; 恭工 a word of the user's, coded aaaa by the rules
        val shared = object : SharedWords by SharedWords.NONE {
            override fun words(prefix: String) = if ("aaaa".startsWith(prefix)) listOf("aaaa" to "恭工", "aaaa" to "工恭") else emptyList()
            override fun leadsAnywhere(prefix: String) = "aaaa".startsWith(prefix)
            override fun blocked(text: String) = text == "工" || text == "恭工"
        }
        assertEquals(listOf("工", "恭恭敬敬", "工恭"), session(shared = shared).type("aaaa").second.candidates)
    }

    @Test
    fun aWordFromOutsideTheTableNeverTakesAUniqueFullCodesPlace() {
        // 你们 is the user's, coded wqiy by no rule: say it were
        val shared = object : SharedWords by SharedWords.NONE {
            override fun words(prefix: String) = if ("wqiy".startsWith(prefix)) listOf("wqiy" to "你们") else emptyList()
            override fun leadsAnywhere(prefix: String) = "wqiy".startsWith(prefix)
        }
        // wqiy alone in the table commits itself, as one who types it blind expects (D8)
        assertEquals("你", session(TableOptions.WUBI, shared).type("wqiy").first)
        // where nothing commits itself, the word is offered after the table's own
        assertEquals(listOf("你", "你们"), session(shared = shared).type("wqiy").second.candidates)
        // nor where the code leads on: wqi is no full code
        assertEquals(listOf("你", "你们"), session(TableOptions.WUBI, shared).type("wqi").second.candidates)
    }

    // two words from outside the table under kkkk, after its own 叫 and 员
    private val outside = object : SharedWords by SharedWords.NONE {
        override fun words(prefix: String) = if ("kkkk".startsWith(prefix)) listOf("kkkk" to "叫员", "kkkk" to "员叫") else emptyList()
        override fun leadsAnywhere(prefix: String) = "kkkk".startsWith(prefix)
    }

    // review R11 F2722/F2723: 调频 off, the words from outside the table among themselves 「首选以外」,
    // in the places they left; the table's own stay where they are
    @Test
    fun withoutOrderByUseTheWordsFromOutsideTheTableAreOrderedAmongThemselves() {
        val t = session(TableOptions.WUBI.copy(autoSelect = false, orderByUse = false), outside)
        assertEquals(listOf("叫", "员", "叫员", "员叫"), t.type("kkkk").second.candidates)
        assertEquals("员叫", t.apply(Select(3)).commit)
        assertEquals("员叫", t.apply(Select(t.type("kkkk").second.candidates.indexOf("员叫"))).commit)
        assertEquals(listOf("叫", "员", "员叫", "叫员"), t.type("kkkk").second.candidates)
    }

    // review R11 F2723: 调频 on, a word of the user's from pinyin not used here is not first for being theirs
    @Test
    fun withOrderByUseAWordOfTheUsersFromPinyinNotUsedHereIsAfterTheTablesOwn() {
        val mine = object : Habits by habits {
            override fun mineOrder(text: String) = if (text == "员叫") 1L else -1L
        }
        val language = learning(learner, mine)
        val t = TableSession(wubi, TableOptions.WUBI.copy(autoSelect = false), shared = outside, language = language)
        assertEquals(listOf("叫", "员"), t.type("kkkk").second.candidates.take(2))
    }

    // review C S3: 「用我的输入习惯」 off, the habits are none, but a block stays (U-93, C26)
    @Test
    fun aBlockUnderACodeStaysWithTheHabitsOff() {
        learner.habits.blockUnder(Code(kind, "aaaa"), "恭恭敬敬", on = true)
        val language = learning(learner, Habits.OFF, learner.habits::blockedUnder)
        val off = TableSession(wubi, TableOptions.WUBI.copy(autoSelect = false), language = language)
        assertEquals(listOf("工"), off.type("aaaa").second.candidates)
    }

    // review C N1: a lone entry blocked under its code leaves the words from outside in, the one there
    // then the code's alone; a row the table has twice is still one entry alone
    @Test
    fun aLoneEntryBlockedOrTwiceIsStillWhatD8Means() {
        val shared = object : SharedWords by SharedWords.NONE {
            override fun words(prefix: String) = if ("wqiy".startsWith(prefix)) listOf("wqiy" to "你们") else emptyList()
            override fun leadsAnywhere(prefix: String) = "wqiy".startsWith(prefix)
        }
        learner.habits.blockUnder(Code(kind, "wqiy"), "你", on = true)
        assertEquals(listOf("你们"), session(shared = shared).type("wqiy").second.candidates)
        assertEquals("你们", session(TableOptions.WUBI, shared).type("wqiy").first)
        val twice = dictionary("wqiy 他", "wqiy 他")
        val language = learning(learner, habits)
        assertEquals("他", TableSession(twice, TableOptions.WUBI, shared = shared, language = language).type("wqiy").first)
    }

    // review C N2: a code with the matching key is no code to learn under; the whole commit still is
    @Test
    fun aWildcardCodeIsNotLearnedUnder() {
        val t = session()
        t.type("aazz")
        t.apply(Select(0))
        assertTrue(recorded.single().whole)
    }

    // review C S4: a field that keeps no pairs (U-91) gets none from a table either
    @Test
    fun aFieldsLearningFlagsHoldInATable() {
        val t = session()
        t.apply(Context("", learnFlags = Commit.NO_PAIRS))
        t.type("g")
        t.apply(Select(0))
        t.type("aaaa")
        t.apply(Select(1))
        assertTrue(learner.log.state.pairs.isEmpty())
        assertTrue("恭恭敬敬" in learner.used())
    }

    @Test
    fun noneIsPinnedOrBlockedUnderAWildcardNorWithoutALearningRecord() {
        val t = session()
        t.type("aazz")
        assertTrue(t.offers(0).none { it == Offer.PIN || it == Offer.BLOCK })
        val none = TableSession(wubi, TableOptions.WUBI.copy(autoSelect = false))
        none.type("aaaa")
        assertEquals(emptySet<Offer>(), none.offers(1))
        assertEquals(listOf("工", "恭恭敬敬"), none.apply(Pin(1)).candidates)
        assertEquals(listOf("工", "恭恭敬敬"), none.apply(Block(1)).candidates)
    }

    // TBL-15 (review-int-runtime M1): 调频 on, a code's words by the user's picks under it, as pinyin's (U-02)
    @Test
    fun withOrderByUseAWordPickedTwiceRunningUnderItsCodeIsFirstThere() {
        val t = session()
        assertEquals(listOf("叫", "员"), t.type("kkkk").second.candidates)
        assertEquals(Tier.DICTIONARY, t.why(0)!!.tier)
        t.apply(Select(1))
        // once: before those not used, the first kept
        assertEquals(listOf("叫", "员"), t.type("kkkk").second.candidates)
        // what 为什么排在这 says: used here once, under the code typed (U-81)
        val why = t.why(1)!!
        assertEquals(Tier.USED, why.tier)
        assertEquals(1, why.uses)
        assertEquals(Code(kind, "kkkk"), why.code)
        assertEquals("员", t.apply(Select(1)).commit)
        // twice running over the first: first
        assertEquals(listOf("员", "叫"), t.type("kkkk").second.candidates)
        assertEquals(Tier.FIRST_HERE, t.why(0)!!.tier)
    }

    // verify-int-step1a G1: 一、二级简码 stay in order under 调频 whatever is picked, and say no why (TBL-13)
    @Test
    fun withOrderByUseAShortCodesWordsNeverMove() {
        val short = dictionary("a 工", "a 戈", "aa 式", "aa 戒", "aaaa 工")
        val t = TableSession(short, TableOptions.WUBI.copy(autoSelect = false), language = learning(learner, habits))
        for (code in listOf("a", "aa")) {
            val shown = t.type(code).second.candidates
            repeat(3) {
                t.apply(Reset)
                t.type(code)
                assertEquals(shown[1], t.apply(Select(1)).commit)
            }
            assertEquals(shown, t.type(code).second.candidates)
            assertNull(t.why(1))
            t.apply(Reset)
        }
    }

    // 调频 off: the table's own order stays whatever is picked (U-28)
    @Test
    fun withoutOrderByUseATablesOwnWordsKeepTheirPlace() {
        val t = session(TableOptions.WUBI.copy(autoSelect = false, orderByUse = false))
        repeat(3) {
            t.type("kkkk")
            assertEquals("员", t.apply(Select(1)).commit)
        }
        assertEquals(listOf("叫", "员"), t.type("kkkk").second.candidates)
        assertEquals(Tier.DICTIONARY, t.why(1)!!.tier)
        assertEquals(3, t.why(1)!!.uses)
    }
}
