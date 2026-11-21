/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.data.pinyinDataOf
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.session.Action.Backspace
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Pin
import org.fcitx.fcitx5.android.engine.session.Action.Reset
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.session.Action.Unpin
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 双拼's 简拼 in the middle in a session: a typed word read by keys alone goes first, or second where the code reads whole syllables. */
class PinyinSessionAbbrevTest {

    // more of xian than a page, each likelier than 下面 and 小明 read by x and m alone
    private val data = pinyinDataOf(
        Triple("先", "xian", -2f), Triple("现", "xian", -2.1f), Triple("线", "xian", -2.2f), Triple("县", "xian", -2.3f),
        Triple("显", "xian", -2.4f), Triple("仙", "xian", -2.5f), Triple("鲜", "xian", -2.6f), Triple("险", "xian", -2.7f),
        Triple("下面", "xia mian", -3f), Triple("下", "xia", -2.5f), Triple("面", "mian", -2.5f),
        Triple("小", "xiao", -2.5f), Triple("明", "ming", -2.5f), Triple("小米", "xiao mi", -3.5f), Triple("小秘密", "xiao mi mi", -4f),
        Triple("什么", "shen me", -2f), Triple("时候", "shi hou", -2f), Triple("什么时候", "shen me shi hou", -3f),
        Triple("快", "kuai", -2f), Triple("块", "kuai", -2.1f), Triple("筷", "kuai", -2.2f), Triple("可以", "ke yi", -3f),
        Triple("原因", "yuan yin", -2.5f), Triple("元", "yuan", -2.5f), Triple("一日游", "yi ri you", -4f),
        Triple("一", "yi", -2f), Triple("日", "ri", -2.5f), Triple("游", "you", -2.5f),
    )

    // the whole list in a page: what the bar shows first is the app's page of 7
    private val pageSize = 20

    private val kind = Kinds.shuangpin("Ziranma")

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    private fun Session.page(keys: String) = type(keys).candidates.take(EngineSettings.DEFAULT_PAGE_SIZE).also { apply(Reset) }

    private fun Session.pick(keys: String, text: String) {
        val shown = type(keys).candidates
        assertTrue("$keys: $text in $shown", text in shown)
        apply(Select(shown.indexOf(text)))
        apply(Reset)
    }

    @Test
    @Uat("SP-34", "SP-55", "SP-59")
    fun aWordPickedInFullGoesSecondUnderAPairItsInitialsSpell() {
        val learner = Learner(data)
        val s = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false, user = learner.model, learnTo = learner.learnTo(kind))
        val fresh = s.page("xm")
        assertEquals("先", fresh.first())
        assertTrue("$fresh", "下面" !in fresh)
        repeat(2) { s.pick("xwmm", "下面") }
        assertEquals("下面", s.page("xwmm").first())
        assertEquals(listOf("先", "下面") + fresh.drop(1).take(5), s.page("xm"))
        // where the code does not read whole, x then m begun, the user's word goes first: the whole dictionary read still
        val apart = s.page("x'm")
        assertEquals("下面", apart.first())
        assertTrue("小米, never typed", "小米" in s.type("x'm").candidates.also { s.apply(Reset) })
    }

    // ' is no key: x'm and xm learn under one code, so a word corrected to twice or pinned apart goes first under the pair too
    @Test
    fun xmLearnsWhatXApartMLearns() {
        val learner = Learner(data)
        val s = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false, user = learner.model, learnTo = learner.learnTo(kind))
        assertEquals("先", s.page("xm").first())
        repeat(2) { s.pick("x'm", "小米") }
        assertEquals("小米", s.page("xm").first())
        val pinned = Learner(data)
        val p = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false, user = pinned.model, learnTo = pinned.learnTo(kind))
        p.type("x'm")
        p.apply(Action.Pin(p.candidates(0, pageSize).indexOfFirst { it.text == "下面" }.also { check(it >= 0) }))
        p.apply(Reset)
        assertEquals("下面", p.page("xm").first())
    }

    @Test
    @Uat("SP-35")
    fun aWordAddedByHandGoesSecondUnderAPairItsInitialsSpell() {
        val learner = Learner(data)
        learner.model.listByHand(UserModel.Entry("小明", intArrayOf(Syllables.id("xiao"), Syllables.id("ming"))))
        fun session(added: Set<String>) = PinyinSession(
            data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false, user = learner.model, learnTo = learner.learnTo(kind), addedWords = { added },
        )
        val none = session(emptySet()).page("xm")
        val added = session(setOf("小明"))
        assertEquals("先", none.first())
        assertEquals("小明", added.page("xm")[1])
        // in full and by its initials apart it is put in, as any word added by hand
        assertEquals("小明", added.page("xcmy").first())
        assertEquals("小明", added.page("x'm").first())
    }

    @Test
    fun whatIsPinnedOrPickedUnderACodeReadWholeIsRankedThereAsItReads() {
        val learner = Learner(data)
        val s = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false, user = learner.model, learnTo = learner.learnTo(kind))
        // never typed, 可以 is not read by k and y alone: only what was typed or chosen is
        assertTrue("可以" !in s.type("ky").candidates.also { s.apply(Reset) })
        s.pick("keyi", "可以")
        val fresh = s.type("ky").candidates.also { s.apply(Reset) }
        // typed, it is read by k and y alone; ky reads kuai whole, so second
        assertEquals(listOf("快", "可以"), fresh.take(2))
        val pinned = s.type("ky").let { s.apply(Pin(it.candidates.indexOf("可以"))) }
        assertEquals("可以", pinned.candidates.first())
        s.apply(Reset)
        assertEquals("可以", s.page("ky").first())
        s.type("ky").let { s.apply(Unpin(it.candidates.indexOf("可以"))) }
        s.apply(Reset)
        assertEquals(fresh, s.type("ky").candidates.also { s.apply(Reset) })
        s.pick("ky", "可以")
        assertEquals(listOf("快", "可以"), s.page("ky").take(2))
        s.pick("ky", "可以")
        assertEquals(listOf("可以", "快"), s.page("ky").take(2))
    }

    @Test
    fun backspaceOverAKeyReadAloneReadsWhatIsLeftAsTypedAfresh() {
        val learner = Learner(data)
        fun session() = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false, user = learner.model, learnTo = learner.learnTo(kind))
        val s = session()
        s.pick("ufmeuihb", "什么时候")
        val whole = s.type("ufmuh")
        assertTrue("$whole", "什么时候" in whole.candidates)
        // each key taken off reads what is left as if typed so, down to uf; and the h again as at first
        for (n in 4 downTo 2) {
            val back = s.apply(Backspace)
            val fresh = session().type("ufmuh".take(n))
            assertEquals("${"ufmuh".take(n)}", fresh.preedit to fresh.candidates, back.preedit to back.candidates)
        }
        val again = s.type("muh")
        assertEquals(whole.preedit to whole.candidates, again.preedit to again.candidates)
    }

    // the first time, a word is typed in whole syllables
    @Test
    fun aKeyReadAloneReadsOnlyWordsTypedBefore() {
        val learner = Learner(data)
        val s = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false, user = learner.model, learnTo = learner.learnTo(kind))
        // nothing typed yet: ufmuh is shen, mu, and h begun; x then mian is no 下面
        val before = s.page("ufmuh")
        assertTrue("$before", "什么时候" !in before)
        val xmm = s.page("xmm")
        assertTrue("下面" !in xmm)
        s.pick("ufmeuihb", "什么时候")
        assertTrue("什么时候" in s.page("ufmuh"))
        assertTrue("下面" !in s.page("xmm"))
        s.pick("xwmm", "下面")
        // read there, x alone then mian whole: not read alone, so not put before the first
        val typed = s.page("xmm")
        assertTrue("$typed", "下面" in typed)
        assertEquals(xmm.first(), typed.first())
        // nor half typed: 小秘密 typed, x m m each alone, goes first, the gate shut
        s.pick("xcmimi", "小秘密")
        assertEquals("小秘密", s.page("xmm").first())
        // where whole syllables read the code, their first is still first
        assertEquals("先", s.page("xm").first())
    }

    @Test
    @Uat("SP-46")
    fun withNoHabitsAKeyIsNotReadAlone() {
        val s = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false)
        assertTrue("什么时候" !in s.page("ufmuh"))
        assertEquals("什么时候", s.page("ufmeuihb").first())
    }

    // typed counts as long as the counts do: 30 typing days (LearnState.WINDOW); a word added by hand for good
    @Test
    @Uat("SP-44")
    fun aWordNotTypedForThirtyTypingDaysIsReadByWholeSyllablesOnlyAgain() {
        val learner = Learner(data)
        val s = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false, user = learner.model, learnTo = learner.learnTo(kind))
        s.pick("ufmeuihb", "什么时候")
        s.pick("xwmm", "下面")
        learner.model.listByHand(UserModel.Entry("小明", intArrayOf(Syllables.id("xiao"), Syllables.id("ming"))))
        assertTrue("什么时候" in s.page("ufmuh"))
        assertTrue("下面" in s.page("xmm"))
        repeat(LearnState.WINDOW) {
            learner.clock.days(1)
            s.pick("xm", "先")
        }
        assertTrue("什么时候" !in s.page("ufmuh"))
        assertTrue("下面" !in s.page("xmm"))
        assertTrue("小明" in s.page("xmy"))
        s.pick("ufmeuihb", "什么时候")
        assertTrue("什么时候" in s.page("ufmuh"))
    }

    // a word pinned under some keys is one typed while it is pinned, whenever last typed
    @Test
    @Uat("SP-44")
    fun aWordPinnedIsReadByAKeyAloneThoughNotTypedForThirtyTypingDays() {
        val learner = Learner(data)
        val s = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false, user = learner.model, learnTo = learner.learnTo(kind))
        s.pick("keyi", "可以")
        s.type("ky").let { s.apply(Pin(it.candidates.indexOf("可以"))) }
        s.apply(Reset)
        assertEquals("可以", s.page("ky").first())
        repeat(LearnState.WINDOW) {
            learner.clock.days(1)
            s.pick("xm", "先")
        }
        assertEquals("可以", s.page("ky").first())
        val unpinned = s.type("ky").let { s.apply(Unpin(it.candidates.indexOf("可以"))) }
        assertTrue("${unpinned.candidates}", "可以" !in unpinned.candidates)
        s.apply(Reset)
        assertTrue("可以" !in s.page("ky"))
    }

    @Test
    @Uat("SP-44")
    fun aWordPickedUnderAKeyReadAloneIsTypedThatDay() {
        val learner = Learner(data)
        val s = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false, user = learner.model, learnTo = learner.learnTo(kind))
        s.pick("ufmeuihb", "什么时候")
        repeat(LearnState.WINDOW - 1) {
            learner.clock.days(1)
            s.pick("xm", "先")
        }
        s.pick("ufmuh", "什么时候")
        repeat(LearnState.WINDOW - 1) {
            learner.clock.days(1)
            s.pick("xm", "先")
        }
        assertTrue("什么时候" in s.page("ufmuh"))
        learner.clock.days(1)
        s.pick("xm", "先")
        assertTrue("什么时候" !in s.page("ufmuh"))
    }

    // a first commit marks only its own nodes, not every node anew on the next key
    @Test
    fun aWordTypedForTheFirstTimeDoesNotMarkEveryTypedWordsNodesAnew() {
        val learner = Learner(data)
        val s = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false, user = learner.model, learnTo = learner.learnTo(kind))
        s.pick("ufmeuihb", "什么时候")
        assertTrue("什么时候" in s.page("ufmuh"))
        val marked = learner.model.typedNodes.indexed
        s.pick("xwmm", "下面")
        s.pick("keyi", "可以")
        learner.model.listByHand(UserModel.Entry("小明", intArrayOf(Syllables.id("xiao"), Syllables.id("ming"))))
        assertTrue("下面" in s.page("xmm"))
        assertTrue("可以" in s.page("ky"))
        assertTrue("小明" in s.page("xmy"))
        assertEquals(marked, learner.model.typedNodes.indexed)
        learner.model.unlistByHand(UserModel.Entry("小明", intArrayOf(Syllables.id("xiao"), Syllables.id("ming"))))
        assertTrue("小明" !in s.page("xmy"))
        assertEquals(marked + 1, learner.model.typedNodes.indexed)
    }

    @Test
    fun aWordAddedByHandIsKeptThoughATypedWordReadsItsKeysAlone() {
        val alone = pinyinDataOf(Triple("王", "wang", -2f), Triple("汪", "wang", -4f), Triple("小明", "xiao ming", -2f), Triple("吃饭", "chi fan", -2f))
        val model = UserModel(alone.dictionary, alone.vocabulary, index = alone.wordIndex)
        model.listByHand(UserModel.Entry("汪小明", intArrayOf(Syllables.id("wang"), Syllables.id("xiao"), Syllables.id("ming"))))
        val learner = Learner(alone)
        fun session() = PinyinSession(
            alone, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false, user = model, learnTo = learner.learnTo(kind), addedWords = { setOf("汪小明") },
        )
        assertEquals("汪小明吃饭", session().page("whxcmyiifj").first())
        model.own(UserModel.Entry("望晓梅雨", intArrayOf(Syllables.id("wang"), Syllables.id("xiao"), Syllables.id("mei"), Syllables.id("yu"))))
        val read = session().type("whxcmyiifj").candidates
        assertTrue("$read", "望晓梅雨" in read)
        assertEquals("汪小明吃饭", read.first())
    }

    @Test
    @Uat("SP-43")
    fun aWordAddedByHandIsReadByAKeyAlone() {
        val model = UserModel(data.dictionary, data.vocabulary, index = data.wordIndex)
        fun session() = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false, user = model, addedWords = { setOf("小明") })
        assertTrue("小明" !in session().page("xmy"))
        model.listByHand(UserModel.Entry("小明", intArrayOf(Syllables.id("xiao"), Syllables.id("ming"))))
        assertTrue("小明" in session().page("xmy"))
    }

    // where the code also reads whole syllables with the last key begun (the gate), they go second
    @Test
    @Uat("SP-30", "SP-56")
    fun wordsTypedAllByInitialsGoSecondOnTheWayToAWordTypedInFull() {
        val learner = Learner(data)
        val s = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false, user = learner.model, learnTo = learner.learnTo(kind))
        val fresh = s.page("yry")
        s.pick("yiriyb", "一日游")
        assertEquals("一日游", s.page("yry").first())
        s.pick("yryn", "原因")
        assertEquals(listOf(fresh.first(), "一日游"), s.page("yry").take(2))
    }

    @Test
    @Uat("SP-30", "SP-33")
    fun aWordTypedByInitialsStaysWhereItIsOnceAcceptedAndThePickTwiceTakesFirstBack() {
        val learner = Learner(data)
        val s = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false, user = learner.model, learnTo = learner.learnTo(kind))
        val fresh = s.page("yry")
        s.pick("yiriyb", "一日游")
        assertEquals(listOf("一日游", fresh.first()), s.page("yry").take(2))
        s.type("yry")
        s.apply(Select(0))
        s.apply(Reset)
        assertEquals(listOf("一日游", fresh.first()), s.page("yry").take(2))
        // the gate: the next by syllables, then the word typed by initials
        repeat(2) { s.pick("yry", fresh.first()) }
        assertEquals(listOf(fresh.first(), fresh[1], "一日游"), s.page("yry").take(3))
    }

    @Test
    @Uat("SP-56")
    fun theGateIsOpenedByAWordTypedPastTheFirstWordsRead() {
        val many = pinyinDataOf(
            *(0 until 70).map { Triple("元" + ('\u4e00' + 300 + it), "yuan yi", -2f - it * 0.01f) }.toTypedArray(),
            Triple("原因", "yuan yin", -9f), Triple("一日游", "yi ri you", -4f), Triple("一", "yi", -2f), Triple("日", "ri", -2.5f), Triple("游", "you", -2.5f),
        )
        val learner = Learner(many)
        val s = PinyinSession(many, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false, user = learner.model, learnTo = learner.learnTo(kind))
        val fresh = s.page("yry")
        s.pick("yiriyb", "一日游")
        assertEquals("一日游", s.page("yry").first())
        s.pick("yryn", "原因")
        val first = s.page("yry")
        assertEquals(listOf(fresh.first(), "一日游"), first.take(2))
        val all = s.type("yry").let { s.candidates(0, 200).map { it.text } }
        s.apply(Reset)
        assertTrue("${all.size}", "原因" in all)
        assertEquals(first.take(2), all.take(2))
    }

    @Test
    fun aVowelsKeyReadAsALeadIsAKeyAlone() {
        val vowels = pinyinDataOf(
            Triple("啊", "a", -2f), Triple("一", "yi", -2f), Triple("呀", "ya", -2.2f), Triple("啊呀", "a ya", -3.5f), Triple("阿姨", "a yi", -4f),
        )
        val learner = Learner(vowels)
        val s = PinyinSession(vowels, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false, user = learner.model, learnTo = learner.learnTo(kind))
        val fresh = s.page("ay")
        assertEquals("啊呀", fresh.first())
        s.pick("aayi", "阿姨")
        assertEquals(listOf("阿姨", "啊呀"), s.page("ay").take(2))
    }
}
