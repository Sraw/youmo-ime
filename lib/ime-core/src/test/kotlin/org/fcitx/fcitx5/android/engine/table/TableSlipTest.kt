/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.pinyin.Near
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.handwriting.handwritingData
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.session.Action.Backspace
import org.fcitx.fcitx5.android.engine.session.Action.Cursor
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A key tapped off-centre in a table (061-neighbour-table): read as the key next to it only where
 * it would make an empty code (空码) or commit a code (顶字) and that key leads on; a code with
 * entries, and typing on target, as without a place; a code so read never commits itself.
 */
class TableSlipTest {

    // a QWERTY key 36 by 50 dp: w left of e, r right of it, d below
    private fun e(dx: Float, dy: Float = 0f) = Touch(dx, dy, 36f, 50f, listOf(Near('w', -1f, 0f), Near('r', 1f, 0f), Near('d', 0.25f, 1f)))

    // 仓颉's and 二笔's way: a key leading nowhere goes into the code, an empty code a space drops
    private val noTopping = TableOptions(noMatchAutoSelectLength = 0)

    private val table = dictionary("a 工", "ab 丙", "abr 甲", "abrr 甲甲", "abw 乙", "abww 乙乙", "abd 丁", "abdd 丁丁", "r 白", "e 月", "ee 朋")

    private fun session(options: TableOptions = noTopping, slips: Boolean = true, dictionary: TableDictionary = table) =
        TableSession(dictionary, options, slips = if (slips) TableSlips.SLIPS else null)

    private fun TableSession.tap(keys: String, last: Char, touch: Touch): Pair<String, Snapshot> {
        val (commit, _) = type(keys)
        val s = apply(Key(last, touch = touch))
        return commit + s.commit to s
    }

    @Test
    @Uat("TBL-54")
    fun aKeyThatMakesAnEmptyCodeIsReadAsTheOneItsTapLeansTo() {
        // abe leads nowhere; the tap at e's right edge, toward r
        val (_, s) = session().tap("ab", 'e', e(0.45f))
        assertEquals("abr", s.preedit)
        assertEquals(listOf("甲", "甲甲"), s.candidates)
        // toward w the other way, toward d down
        assertEquals("abw", session().tap("ab", 'e', e(-0.45f)).second.preedit)
        assertEquals("abd", session().tap("ab", 'e', e(0.1f, 0.45f)).second.preedit)
        // a tap in the key's middle: as typed
        assertEquals("abe", session().tap("ab", 'e', e(0f)).second.preedit)
        // the slips off (纠正错拼和按偏的键): the empty code as typed, which a space drops
        val off = session(slips = false)
        val (_, typed) = off.tap("ab", 'e', e(0.45f))
        assertEquals("abe", typed.preedit)
        assertEquals(emptyList<String>(), typed.candidates)
        assertEquals("", off.apply(Select(0)).commit)
    }

    @Test
    @Uat("TBL-54")
    fun theNearestKeyThatLeadsOnIsTaken() {
        val nowhereRight = dictionary("ab 丙", "abw 乙", "abww 乙乙", "abd 丁", "abdd 丁丁")
        // r would be nearer, but abr leads nowhere: the next nearest that leads on
        assertEquals("abd", session(dictionary = nowhereRight).tap("ab", 'e', e(0.45f, 0.35f)).second.preedit)
        // none around leads on: the empty code as typed
        val none = dictionary("ab 丙", "abc 甲")
        assertEquals("abe", session(dictionary = none).tap("ab", 'e', e(0.45f)).second.preedit)
    }

    @Test
    @Uat("TBL-55")
    fun aCodeWithEntriesIsNeverChanged() {
        val withE = dictionary("ab 丙", "abe 月", "abee 月月", "abr 甲", "abrr 甲甲")
        assertEquals("abe", session(dictionary = withE).tap("ab", 'e', e(0.49f)).second.preedit)
    }

    @Test
    @Uat("TBL-55")
    fun aCodesFirstKeyIsNeverRead() {
        // nothing typed: a key leading nowhere is the app's, as without a place
        val noE = dictionary("r 白", "ra 白白")
        val s = session(dictionary = noE).apply(Key('e', touch = e(0.49f)))
        assertFalse(s.handled)
        assertEquals("", s.preedit)
    }

    @Test
    @Uat("TBL-55")
    fun aFullCodesNextKeyStartsTheNextCode() {
        // abrr is full: e starts a code of its own, committing 甲甲 as without a place
        val (commit, s) = session().tap("abrr", 'e', e(0.49f))
        assertEquals("甲甲", commit)
        assertEquals("e", s.preedit)
    }

    @Test
    @Uat("TBL-55")
    fun theWildcardIsNeverReadAsAnotherKey() {
        val wild = TableOptions(noMatchAutoSelectLength = 0, matchingKey = 'z')
        // a code with the wildcard in it: as typed
        assertEquals("aze", session(wild).tap("az", 'e', e(0.49f)).second.preedit)
    }

    // 五笔's way (顶字): a key leading nowhere commits the first candidate and starts the next code
    private val wubi = dictionary("w 人", "wq 你", "wqiy 你", "wqvb 你好", "o 火", "oo 炎", "u 立", "uu 闭", "y 主")

    // o tapped toward i on its left, [dx] across it
    private fun oTowardI(dx: Float) = Touch(dx, 0f, 36f, 50f, listOf(Near('i', -1f, 0f), Near('p', 1f, 0f)))

    // u tapped toward y on its left
    private fun uTowardY(dx: Float) = Touch(dx, 0f, 36f, 50f, listOf(Near('y', -1f, 0f), Near('i', 1f, 0f)))

    private fun language(): TableLanguage = handwritingData.let { TableLanguage(TextWords(it.model, it.wordIndex)) }

    @Test
    @Uat("TBL-55")
    fun aKeyAfterAWholeCodeIsReadOnlyAtTheEdge() {
        // 你 by wq, 们 by wqiu; wqii leads on from wqi, so o toward i is read there but for the topping floor
        val wubi = dictionary("w 人", "wq 你", "wqiu 们", "wqii 他", "o 火", "oo 炎", "u 立")
        // wq is 你's whole code: o off-centre is the next code's (顶字), as a 五笔 typist types 简码 then on
        val (commit, s) = session(TableOptions.WUBI, dictionary = wubi).tap("wq", 'o', oTowardI(-0.3f))
        assertEquals("你", commit)
        assertEquals("o", s.preedit)
        // nearer, i costing −0.34: past the floor (−1), wqi, nothing committed
        val (nearer, after) = session(TableOptions.WUBI, dictionary = wubi).tap("wq", 'o', oTowardI(-0.44f))
        assertEquals("", nearer)
        assertEquals("wqi", after.preedit)
        // at the very edge toward i: wqi, nothing committed
        val (edge, read) = session(TableOptions.WUBI, dictionary = wubi).tap("wq", 'o', oTowardI(-0.49f))
        assertEquals("", edge)
        assertEquals("wqi", read.preedit)
        // a part of a code showing a character first (wqi: 他 of wqii), then the next code's key: the same, a tap
        // off-centre (i costing −1.13, read under the empty code's −1.5) is the next code's
        val (part, next) = session(TableOptions.WUBI, dictionary = wubi).tap("wqi", 'o', oTowardI(-0.3f))
        assertEquals("他", part)
        assertEquals("o", next.preedit)
    }

    @Test
    @Uat("TBL-55")
    fun aKeyIsNeverReadAsOneLeadingToTheTextItWouldCommit() {
        // wqi shows 你 first; u even at y's edge: wqiy's first is 你 again, nothing gained, so 顶字
        val (commit, s) = session(TableOptions.WUBI, dictionary = wubi).tap("wqi", 'u', uTowardY(-0.49f))
        assertEquals("你", commit)
        assertEquals("u", s.preedit)
    }

    @Test
    @Uat("TBL-55")
    fun aKeyAfterAWholeCodeIsReadOnlyForACodeTheWordModelWeighsAsMuch() {
        // the fixture's model knows 人 alone: wqi's first, 夔, is weighed less than 人 the key would commit, so 顶字 as typed
        val rare = dictionary("wq 人", "wqiy 夔", "o 火", "oo 炎")
        val (commit, s) = TableSession(rare, TableOptions.WUBI, language = language(), slips = TableSlips.SLIPS).tap("wq", 'o', oTowardI(-0.49f))
        assertEquals("人", commit)
        assertEquals("o", s.preedit)
        // the other way round: wqi read
        val common = dictionary("wq 夔", "wqiy 人", "o 火", "oo 炎")
        val (none, read) = TableSession(common, TableOptions.WUBI, language = language(), slips = TableSlips.SLIPS).tap("wq", 'o', oTowardI(-0.49f))
        assertEquals("", none)
        assertEquals("wqi", read.preedit)
    }

    @Test
    @Uat("TBL-55")
    fun aCodeWithAKeyReadAsItsNeighbourNeverCommitsItself() {
        // ab then e read as r: abr's 甲 is no lone candidate here; abw with one entry (乙) waits, a space commits it
        val lone = dictionary("a 工", "ab 丙", "abw 乙", "abd 丁", "abdd 丁丁", "r 白")
        val full = TableOptions(noMatchAutoSelectLength = 0, autoSelectLength = 3)
        val session = session(full, dictionary = lone)
        val (commit, s) = session.tap("ab", 'e', e(-0.45f))
        assertEquals("", commit)
        assertEquals("abw", s.preedit)
        assertEquals(listOf("乙"), s.candidates)
        assertEquals("乙", session.apply(Select(0)).commit)
        // the key so read deleted and typed on target: it commits itself again
        val again = session(full, dictionary = lone)
        again.tap("ab", 'e', e(-0.45f))
        again.apply(Backspace)
        assertEquals("乙", again.apply(Key('w')).commit)
    }

    @Test
    @Uat("TBL-54")
    fun aCodeReadFromASlipWithOneCandidateIsCommittedByAKeyThatCannotGoOn() {
        val lone = dictionary("a 工", "ab 丙", "abw 乙", "abd 丁", "abdd 丁丁", "r 白", "re 白月")
        val full = TableOptions(noMatchAutoSelectLength = 0, autoSelectLength = 3)
        val session = session(full, dictionary = lone)
        session.tap("ab", 'e', e(-0.45f))
        // r, on target, cannot go on abw: 乙 committed, r the next code's, as after a full code
        val next = session.apply(Key('r'))
        assertEquals("乙", next.commit)
        assertEquals("r", next.preedit)
        // Backspace as after a key that tops (顶字): the key's code goes, then the field's
        assertEquals("", session.apply(Backspace).preedit)
        assertFalse(session.apply(Backspace).handled)
        // a key that goes on (abd's d): no commit
        val on = session(full, dictionary = dictionary("ab 丙", "abw 乙", "abwd 乙丁", "d 丁"))
        on.tap("ab", 'e', e(-0.45f))
        assertEquals("", on.apply(Key('d')).commit)
    }

    @Test
    @Uat("TBL-54")
    fun everyKeyReadKeepsItsMarkWhenAnotherGoes() {
        // ab, e read as r, e read as w (abrw), ⌫: abr still has a key read, so r on target does not commit abrr
        val two = dictionary("ab 丙", "abr 甲", "abrr 甲甲", "abrw 甲乙")
        val full = TableOptions(noMatchAutoSelectLength = 0, autoSelectLength = 4)
        val session = session(full, dictionary = two)
        session.tap("ab", 'e', e(0.45f))
        assertEquals("abrw", session.apply(Key('e', touch = e(-0.45f))).preedit)
        session.apply(Backspace)
        val s = session.apply(Key('r'))
        assertEquals("", s.commit)
        assertEquals("abrr", s.preedit)
        // it waits: a key that cannot go on commits it
        assertEquals("甲甲", session.apply(Key('x')).commit)
    }

    @Test
    @Uat("TBL-54")
    fun aCodeReadFromASlipWaitsOnlyWhereItWouldHaveCommittedItself() {
        // more than one candidate: the next key goes into the code (仓颉's way), nothing committed
        val two = dictionary("ab 丙", "abw 乙", "abw 乚", "r 白")
        val noTop = TableOptions(noMatchAutoSelectLength = 0, autoSelectLength = 3)
        session(noTop, dictionary = two).let {
            it.tap("ab", 'e', e(-0.45f))
            val s = it.apply(Key('r'))
            assertEquals("", s.commit)
            assertEquals("abwr", s.preedit)
        }
        val lone = dictionary("ab 丙", "abw 乙", "r 白")
        // autoSelect off, or a code short of autoSelectLength: no lone candidate commits itself, so none waits
        for (options in listOf(noTop.copy(autoSelect = false), noTop.copy(autoSelectLength = 4))) {
            val session = session(options, dictionary = lone)
            session.tap("ab", 'e', e(-0.45f))
            assertEquals("", session.apply(Key('r')).commit)
        }
    }

    @Test
    @Uat("TBL-54")
    fun theKeyAfterAWaitingCodeIsReadAsAToppingKeyIs() {
        // abw's lone 乙 waits (abwe's 鬱 held back by 生僻字屏蔽, so not shown); abwe leads on all the same
        val lone = dictionary("ab 丙", "abw 乙", "abwe 鬱", "r 白", "e 月")
        val held = TableLanguage(TextWords(handwritingData.model, handwritingData.wordIndex), held = { it == "鬱" })
        fun waiting() = TableSession(lone, TableOptions(noMatchAutoSelectLength = 0, autoSelectLength = 3), language = held, slips = TableSlips.SLIPS)
            .also { it.tap("ab", 'e', e(-0.45f)) }
        fun rTowardE(dx: Float) = Touch(dx, 0f, 36f, 50f, listOf(Near('e', -1f, 0f), Near('t', 1f, 0f)))
        // the next key would commit 乙: r off-centre (e costing −1.13, read under the empty code's −1.5) is the next code's
        val s = waiting().apply(Key('r', touch = rTowardE(-0.3f)))
        assertEquals("乙", s.commit)
        assertEquals("r", s.preedit)
        // at the very edge, past the topping floor: read as e, abwe, nothing committed
        val edge = waiting().apply(Key('r', touch = rTowardE(-0.49f)))
        assertEquals("", edge.commit)
        assertEquals("abwe", edge.preedit)
    }

    @Test
    @Uat("TBL-55")
    fun aWaitingCodeIsCommittedOnceWhereTheTableTops() {
        // 五笔: wq, o read as i (wqi: 们 of wqiu), u: wqiu's lone 们 waits; o cannot go on, it commits 们 once
        val wubi = dictionary("w 人", "wq 你", "wqiu 们", "o 火", "oo 炎", "u 立")
        val session = session(TableOptions.WUBI, dictionary = wubi)
        session.tap("wq", 'o', oTowardI(-0.49f))
        val waits = session.apply(Key('u'))
        assertEquals("", waits.commit)
        assertEquals("wqiu", waits.preedit)
        val next = session.apply(Key('o'))
        assertEquals("们", next.commit)
        assertEquals("o", next.preedit)
    }

    @Test
    @Uat("TBL-54")
    fun aWaitingCodeIsCommittedByPunctuationASpaceOrASelectionKeyAsAnyCode() {
        val lone = dictionary("ab 丙", "abw 乙")
        val options = TableOptions(noMatchAutoSelectLength = 0, autoSelectLength = 3, selectionKeys = "123")
        fun waiting() = session(options, dictionary = lone).also { it.tap("ab", 'e', e(-0.45f)) }
        val comma = waiting().apply(Key(','))
        assertEquals("乙", comma.commit)
        assertFalse(comma.handled)
        assertEquals("乙", waiting().apply(Select(0)).commit)
        assertEquals("乙", waiting().apply(Key('1')).commit)
    }

    @Test
    @Uat("TBL-55")
    fun ziranmaTopsAsWubiDoes() {
        val ziranma = dictionary("w 人", "wq 你", "wqiu 们", "o 火", "oo 炎")
        val (commit, s) = session(TableOptions.ZIRANMA, dictionary = ziranma).tap("wq", 'o', oTowardI(-0.3f))
        assertEquals("你", commit)
        assertEquals("o", s.preedit)
        assertEquals("wqi", session(TableOptions.ZIRANMA, dictionary = ziranma).tap("wq", 'o', oTowardI(-0.49f)).second.preedit)
    }

    @Test
    @Uat("TBL-54")
    fun erbiNeverTopsAndItsPinyinKeyIsNeverRead() {
        val erbi = dictionary("ab 丙", "abr 甲", "abp 乙", "r 白", "e 月", "ee 朋")
        // ab is a whole code, but 二笔 tops nothing: e off-centre is read at the floor of an empty code
        assertEquals("abr", session(TableOptions.ERBI, dictionary = erbi).tap("ab", 'e', e(0.3f)).second.preedit)
        // where the table tops (五笔), the same tap is the next code's
        assertEquals("丙", session(TableOptions.WUBI, dictionary = erbi).tap("ab", 'e', e(0.3f)).first)
        // [ (拼音反查) at its edge toward p: no key of the codes, never read; it commits what is typed, the app's
        val bracket = Touch(-0.49f, 0f, 36f, 50f, listOf(Near('p', -1f, 0f), Near(']', 1f, 0f)))
        val (commit, s) = session(TableOptions.ERBI, dictionary = erbi).tap("ab", '[', bracket)
        assertEquals("丙", commit)
        assertFalse(s.handled)
    }

    @Test
    @Uat("TBL-55")
    fun wubiPinyinReadsNoKeyAsOneLeadingToPinyinAlone() {
        // abr leads to a pinyin entry only: ab's 丙 topped by e, as typed
        val table = dictionary("ab 丙", "@abr 甲", "e 月", "ee 朋", header = mapOf("拼音" to "@"))
        val (commit, s) = session(TableOptions.WUBI_PINYIN, dictionary = table).tap("ab", 'e', e(0.49f))
        assertEquals("丙", commit)
        assertEquals("e", s.preedit)
    }

    @Test
    @Uat("TBL-54")
    fun aKeyPutInMidCodeIsNeverReadAndMovesTheMarks() {
        val mid = dictionary("ab 丙", "abr 甲", "aeb 戊", "aebr 乙")
        val full = TableOptions(noMatchAutoSelectLength = 0, autoSelectLength = 4)
        val session = session(full, dictionary = mid)
        session.tap("ab", 'e', e(0.45f))
        // the cursor after a: e put in there as typed, though its tap leans to r; aebr's lone 乙 waits, r still read
        session.apply(Cursor(1))
        val s = session.apply(Key('e', touch = e(0.45f)))
        assertEquals("", s.commit)
        assertEquals("aebr", s.preedit)
        // the mark moved on with r: r deleted, it is gone, and r on target commits aebr's 乙 itself
        session.apply(Cursor(4))
        session.apply(Backspace)
        assertEquals("乙", session.apply(Key('r')).commit)
    }

    @Test
    @Uat("TBL-54")
    fun noKeyIsReadAsASelectionKeyNorAnEndKey() {
        // ab: e's neighbours r and w are a selection key and an end key here: as typed
        val keys = TableOptions(noMatchAutoSelectLength = 0, selectionKeys = "r", endKeys = "w")
        val ends = dictionary("ab 丙", "abr 甲", "abw 乙")
        assertEquals("abe", session(keys, dictionary = ends).tap("ab", 'e', e(0.45f)).second.preedit)
        assertEquals("abe", session(keys, dictionary = ends).tap("ab", 'e', e(-0.45f)).second.preedit)
    }
}
