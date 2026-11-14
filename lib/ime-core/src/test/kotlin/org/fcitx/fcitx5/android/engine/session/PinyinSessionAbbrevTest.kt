/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.learn.Kinds
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

/**
 * 双拼's 简拼 in the middle in a session (自然码): what the user's habits do with it, and editing over
 * it. A code that reads as whole syllables (`xm`, xian) is ranked as before it: what is learned
 * raises nothing there that only the keys read alone read (061-shuangpin-abbrev review M3).
 */
class PinyinSessionAbbrevTest {

    // more of xian than a page, each likelier than 下面 and 小明 read by x and m alone
    private val data = pinyinDataOf(
        Triple("先", "xian", -2f), Triple("现", "xian", -2.1f), Triple("线", "xian", -2.2f), Triple("县", "xian", -2.3f),
        Triple("显", "xian", -2.4f), Triple("仙", "xian", -2.5f), Triple("鲜", "xian", -2.6f), Triple("险", "xian", -2.7f),
        Triple("下面", "xia mian", -3f), Triple("下", "xia", -2.5f), Triple("面", "mian", -2.5f),
        Triple("小", "xiao", -2.5f), Triple("明", "ming", -2.5f),
        Triple("什么", "shen me", -2f), Triple("时候", "shi hou", -2f), Triple("什么时候", "shen me shi hou", -3f),
        // ky is kuai; 可以 by k and y alone
        Triple("快", "kuai", -2f), Triple("块", "kuai", -2.1f), Triple("筷", "kuai", -2.2f), Triple("可以", "ke yi", -3f),
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
    fun aWordPickedInFullRaisesNothingUnderAPairItsInitialsSpell() {
        val learner = Learner(data)
        val s = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false, user = learner.model, learnTo = learner.learnTo(kind))
        val fresh = s.page("xm")
        assertEquals("先", fresh.first())
        assertTrue("$fresh", "下面" !in fresh)
        // xia mian, twice: 下面 the user's, used spelt in full (U-20)
        repeat(2) { s.pick("xwmm", "下面") }
        assertEquals("下面", s.page("xwmm").first())
        assertEquals(fresh, s.page("xm"))
        // where the code does not read whole the user's word is still raised by its initials: x then m begun
        assertTrue("x'm", "下面" in s.page("x'm"))
    }

    @Test
    fun aWordAddedByHandIsNotPutInUnderAPairItsInitialsSpell() {
        val model = UserModel(data.dictionary, data.vocabulary, index = data.wordIndex)
        model.listByHand(UserModel.Entry("小明", intArrayOf(Syllables.id("xiao"), Syllables.id("ming"))))
        val learner = Learner(data)
        fun session(added: Set<String>) = PinyinSession(
            data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false, user = model, learnTo = learner.learnTo(kind), addedWords = { added },
        )
        val none = session(emptySet()).page("xm")
        val added = session(setOf("小明"))
        assertEquals(none, added.page("xm"))
        // in full and by its initials apart it is put in, as any word added by hand
        assertEquals("小明", added.page("xcmy").first())
        assertTrue("x'm", "小明" in added.page("x'm"))
    }

    @Test
    fun whatIsPinnedOrPickedUnderACodeReadWholeIsRankedThereAsItReads() {
        val learner = Learner(data)
        val s = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false, user = learner.model, learnTo = learner.learnTo(kind))
        val fresh = s.type("ky").candidates.also { s.apply(Reset) }
        assertEquals("快", fresh.first())
        val at = fresh.indexOf("可以")
        assertTrue("$fresh", at > 0)
        // pinned under ky itself: first there, though ky reads kuai
        val pinned = s.type("ky").let { s.apply(Pin(it.candidates.indexOf("可以"))) }
        assertEquals("可以", pinned.candidates.first())
        s.apply(Reset)
        assertEquals("可以", s.page("ky").first())
        s.type("ky").let { s.apply(Unpin(it.candidates.indexOf("可以"))) }
        s.apply(Reset)
        assertEquals(fresh, s.type("ky").candidates.also { s.apply(Reset) })
        // picked there: it goes up
        repeat(2) { s.pick("ky", "可以") }
        val picked = s.type("ky").candidates.also { s.apply(Reset) }
        assertTrue("$picked", picked.indexOf("可以") < at)
    }

    @Test
    fun backspaceOverAKeyReadAloneReadsWhatIsLeftAsTypedAfresh() {
        val s = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false)
        val whole = s.type("ufmuh")
        assertTrue("$whole", "什么时候" in whole.candidates)
        // each key taken off reads what is left as if typed so, down to uf; and the h again as at first
        for (n in 4 downTo 2) {
            val back = s.apply(Backspace)
            val fresh = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), pageSize = pageSize, prediction = false).type("ufmuh".take(n))
            assertEquals("${"ufmuh".take(n)}", fresh.preedit to fresh.candidates, back.preedit to back.candidates)
        }
        val again = s.type("muh")
        assertEquals(whole.preedit to whole.candidates, again.preedit to again.candidates)
    }
}
