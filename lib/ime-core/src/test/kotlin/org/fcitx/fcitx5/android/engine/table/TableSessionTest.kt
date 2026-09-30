/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Action.Backspace
import org.fcitx.fcitx5.android.engine.session.Action.CommitRaw
import org.fcitx.fcitx5.android.engine.session.Action.Context
import org.fcitx.fcitx5.android.engine.session.Action.Forget
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.NextPage
import org.fcitx.fcitx5.android.engine.session.Action.Pin
import org.fcitx.fcitx5.android.engine.session.Action.Pick
import org.fcitx.fcitx5.android.engine.session.Action.PreviousPage
import org.fcitx.fcitx5.android.engine.session.Action.Reset
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class TableSessionTest {

    private fun dictionary(vararg entries: String, header: Map<String, String> = emptyMap()) = CodeTable.Builder()
        .header("键码", "abcdefghijklmnopqrstuvwxy")
        .header("码长", "4")
        .apply { header.forEach { (k, v) -> header(k, v) } }
        .rule("e2", "p11+p12+p21+p22")
        .rule("e3", "p11+p21+p31+p32")
        .rule("a4", "p11+p21+p31+n11")
        .apply { entries.forEach { e -> e.split(' ').let { entry(it[0], it[1]) } } }
        .build()
        .toByteArray()
        .let { TableDictionary(CodeTable.load(ByteBuffer.wrap(it))) }

    private val wubi = dictionary(
        "a 工", "a 戈", "aa 式", "aaa 或", "aaaa 工", "aaaa 恭恭敬敬", "aaad 工期", "sie 梢", "sieg 梢",
        "w 人", "wqiy 你", "wun 们", "wqvb 你好", "vbg 好",
        "kkkk 叫", "kkkk 员", "trnt 我",
    )

    private fun session(options: TableOptions = TableOptions.WUBI, pinyin: Session? = null) = TableSession(wubi, options, pinyin)

    /** Types [keys], returning every snapshot's commit joined and the last snapshot. */
    private fun Session.type(keys: String): Pair<String, Snapshot> {
        var commit = ""
        var last: Snapshot? = null
        for (c in keys) last = apply(Key(c)).also { commit += it.commit }
        return commit to last!!
    }

    @Test
    fun exactCodesComeFirstThenLongerOnesShorterFirstEachTextOnce() {
        val (commit, s) = session().type("a")
        assertEquals("", commit)
        assertEquals("a", s.preedit)
        // 工 once, by its shortest code
        assertEquals(listOf("工", "戈", "式", "或", "恭恭敬敬"), s.candidates)
        assertEquals(listOf("", "", "a", "aa", "aaa"), s.hints)
        assertTrue(s.hasNextPage)
        val next = session().apply { type("a") }.apply(NextPage)
        assertEquals(listOf("工期"), next.candidates)
        assertEquals(listOf("aad"), next.hints)
        assertFalse(next.hasNextPage)
    }

    @Test
    fun aLoneCandidateOfTheWholeCodeCommitsItself() {
        val t = session()
        // wqi leads only to 你, whose code is longer: nothing yet
        assertEquals("", t.type("wqi").first)
        val (commit, s) = t.type("y")
        assertEquals("你", commit)
        assertEquals("", s.preedit)
        assertEquals(emptyList<String>(), s.candidates)
        // at any length, not only a full code
        assertEquals("们", session().type("wun").first)
        // and alone once each text shows once: sie and sieg are both 梢
        assertEquals("梢", session().type("sie").first)
    }

    @Test
    fun aKeyPastAFullCodeCommitsTheFirstCandidate() {
        val t = session()
        assertEquals("", t.type("kkkk").first)
        val (commit, s) = t.type("a")
        assertEquals("叫", commit)
        assertEquals("a", s.preedit)
        assertEquals("工", s.candidates.first())
    }

    @Test
    fun aKeyLeadingNowhereCommitsWhatWasTypedAndStartsOver() {
        val t = session()
        val (commit, s) = t.type("wx")
        assertEquals("人", commit)
        // no code starts with x: it is the app's to type, as fcitx hands it on
        assertFalse(s.handled)
        assertEquals("", s.preedit)
        assertEquals("a", t.type("a").second.preedit)
    }

    @Test
    fun aShortCodeRanksFirstHoweverManyLongerOnesComeBeforeItInTheTable() {
        val long = ('a'..'x').flatMap { x -> ('a'..'y').flatMap { y -> ('a'..'y').map { z -> "a$x$y$z 字" } } }
        val t = TableSession(dictionary(*(long + "ay 短").toTypedArray()), TableOptions())
        assertEquals(listOf("短", "字"), t.type("a").second.candidates)
    }

    @Test
    fun pagesTurnNowhereWithoutCandidatesButTheKeyIsNotTheApps() {
        val t = session()
        t.type("wz")
        assertTrue(t.apply(PreviousPage).handled)
        assertTrue(t.apply(NextPage).handled)
        t.apply(Reset)
        assertFalse(t.apply(NextPage).handled)
    }

    @Test
    fun aHostListsAndPicksAmongAllCandidatesWithTheirHints() {
        val t = session()
        val s = t.type("a").second
        // more than a page: not counted until asked for
        assertEquals(-1, s.total)
        assertEquals(listOf(Choice("恭恭敬敬", "aaa"), Choice("工期", "aad")), t.candidates(4, 10))
        assertEquals("工期", t.apply(Pick(5)).commit)
        // a page's worth is counted
        assertEquals(1, t.type("vb").second.total)
        assertTrue(t.apply(Pick(9)).handled)
        t.apply(Reset)
        assertFalse(t.apply(Pick(0)).handled)
        assertTrue(t.candidates(0, 5).isEmpty())
    }

    @Test
    fun theKeysReadAreCodeKeysTheWildcardAndThePinyinKeyToStart() {
        val t = session(pinyin = FakePinyin())
        assertTrue(t.reads('a'))
        assertTrue(t.reads('z'))
        assertFalse(t.reads(','))
        assertFalse(t.reads('1'))
        // 仓颉's wildcard is not a code key, and no pinyin session: no lookup
        val cangjie = session(TableOptions.CANGJIE)
        assertTrue(cangjie.reads('*'))
        assertFalse(cangjie.reads('z'))
        // a pinyin key that is no code key only starts a code
        val lookUpOnly = session(TableOptions(pinyinKey = '`'), FakePinyin())
        assertTrue(lookUpOnly.reads('`'))
        lookUpOnly.type("a")
        assertFalse(lookUpOnly.reads('`'))
        // looking up, pinyin is read, and what it offers listed with codes
        t.type("zwo")
        assertTrue(t.reads('\''))
        assertFalse(t.reads('A'))
        assertEquals(listOf(Choice("窝", "")), t.candidates(1, 5))
        assertEquals(Choice("我", "trnt"), t.candidates(0, 1).single())
    }

    @Test
    fun aKeyEndingACodeCommitsTheFirstCandidateOfThePageShown() {
        val t = session()
        t.type("a")
        t.apply(NextPage)
        assertEquals("工期", t.type("w").first)
    }

    @Test
    fun withoutNoMatchSelectionACodeGrowsUntilFull() {
        val t = session(TableOptions.CANGJIE)
        val (commit, s) = t.type("wx")
        assertEquals("", commit)
        assertEquals("wx", s.preedit)
        // full, then the next key drops the code that has no candidate
        assertEquals("a", t.type("xxa").second.preedit)
    }

    @Test
    fun withoutAutoSelectNothingCommitsByItself() {
        val t = session(TableOptions.WUBI.copy(autoSelect = false))
        assertEquals("", t.type("wqiy").first)
        assertEquals(listOf("你"), t.apply(Key('a')).let { t.apply(Backspace) }.candidates)
        // a lone candidate needs its code long enough when a length is set
        val short = session(TableOptions.WUBI.copy(autoSelectLength = 4))
        assertEquals("", short.type("wun").first)
    }

    @Test
    fun spaceOnACodeWithNoCandidateDropsIt() {
        val t = session(TableOptions.CANGJIE)
        assertEquals("wx", t.type("wx").second.preedit)
        val s = t.apply(Select(0))
        assertTrue(s.handled)
        assertEquals("", s.commit)
        assertEquals("", s.preedit)
        // nothing typed: space is the app's
        assertFalse(t.apply(Select(0)).handled)
    }

    @Test
    fun selectingPagingAndBackspace() {
        val t = session()
        t.type("a")
        assertEquals("戈", t.apply(Select(1)).commit)
        t.type("a")
        assertTrue(t.apply(NextPage).hasPreviousPage)
        // off the page: ignored
        assertEquals("", t.apply(Select(3)).commit)
        assertEquals("工期", t.apply(Select(0)).commit)
        t.type("aa")
        assertEquals("a", t.apply(Backspace).preedit)
        assertEquals("", t.apply(Backspace).preedit)
        assertFalse(t.apply(Backspace).handled)
        assertFalse(t.apply(PreviousPage).handled)
    }

    @Test
    fun enterCommitsTheCodeAsTypedAndOtherKeysCommitTheFirstCandidate() {
        val t = session()
        t.type("aa")
        assertEquals("aa", t.apply(CommitRaw).commit)
        assertFalse(t.apply(CommitRaw).handled)
        t.type("aa")
        val s = t.apply(Key('.'))
        assertEquals("式", s.commit)
        assertFalse(s.handled)
        // with nothing typed a key not in the table is simply the app's
        assertFalse(t.apply(Key('1')).handled)
        t.type("aa")
        assertEquals("", t.apply(Reset).preedit)
        // the app's text drops the input as a reset does
        t.type("aa")
        assertEquals("", t.apply(Context("工")).preedit)
    }

    @Test
    fun theMatchingKeyStandsForAnyKey() {
        val (_, s) = session().type("wziy")
        assertEquals(listOf("你"), s.candidates)
        // the whole code, since what was typed is not its start
        assertEquals(listOf("wqiy"), s.hints)
        // never committed by itself: the user did not type its code
        assertEquals("", s.commit)
        // first, it matches every code as long
        val cangjie = session(TableOptions.CANGJIE)
        assertEquals(listOf("工", "戈", "人"), cangjie.type("*").second.candidates)
    }

    @Test
    fun longerCodesPutWhatWasPickedMostFirst() {
        val t = session()
        t.type("aaaa")
        assertEquals("恭恭敬敬", t.apply(Select(1)).commit)
        assertEquals(listOf("恭恭敬敬", "工"), t.type("aaaa").second.candidates)
        t.apply(Reset)
        // short codes keep the table's order
        t.type("a")
        t.apply(Select(1))
        assertEquals(listOf("工", "戈"), t.type("a").second.candidates.take(2))
        // and without ordering by use, so do long ones
        val plain = session(TableOptions.WUBI.copy(orderByUse = false))
        plain.type("aaaa")
        plain.apply(Select(1))
        assertEquals(listOf("工", "恭恭敬敬"), plain.type("aaaa").second.candidates)
    }

    @Test
    fun theAppsTextKeepsWhatAPhraseIsMadeOfOnlyWhileItEndsWithIt() {
        val stayed = session()
        // its whole code, and nothing else has it: committed as typed
        assertEquals("你", stayed.type("wqiy").first)
        stayed.apply(Context("他说你"))
        stayed.type("wun")
        stayed.apply(Select(0))
        assertEquals(listOf("你们"), stayed.type("wqwu").second.candidates)
        val moved = session()
        moved.type("wqiy")
        moved.apply(Context("你说"))
        moved.type("wun")
        moved.apply(Select(0))
        assertFalse("你们" in moved.type("wqwu").second.candidates)
    }

    @Test
    fun charactersTypedOneByOneBecomeAPhraseTheUsersOncePicked() {
        val t = session()
        t.type("wqiy")
        t.type("wun")
        // 你们 by e2: wq + wu, offered last and never committed by itself
        val (commit, s) = t.type("wqwu")
        assertEquals("", commit)
        assertEquals(listOf("你们"), s.candidates)
        assertEquals("你们", t.apply(Select(0)).commit)
        // picked: now the table's own, so a lone candidate that commits itself
        assertEquals("你们", t.type("wqwu").first)
    }

    @Test
    fun asLibimeHasItAPhraseRunsToTheLongestCodeAndIsSavedOnlyOncePicked() {
        val t = session(TableOptions.WUBI.copy(autoPhraseLength = -1, saveAutoPhraseAfter = -1))
        repeat(4) {
            t.type("wqiy")
            t.type("wun")
        }
        // never saved by count: offered, but waits to be picked
        val (commit, s) = t.type("wqwu")
        assertEquals("", commit)
        assertEquals(listOf("你们"), s.candidates)
        assertEquals("你们", t.apply(Select(0)).commit)
        assertEquals("你们", t.type("wqwu").first)
    }

    @Test
    fun whatIsForgottenIsRankedAsTheTableHasIt() {
        val t = session(TableOptions.WUBI.copy(saveAutoPhraseAfter = -1))
        t.type("aaaa")
        assertEquals("恭恭敬敬", t.apply(Select(1)).commit)
        val learned = t.type("aaaa").second
        assertEquals(listOf("恭恭敬敬", "工"), learned.candidates)
        assertTrue(learned.actionable)
        assertEquals(setOf(Offer.FORGET), t.offers(0))
        assertEquals(emptySet<Offer>(), t.offers(2))
        // no phrases to pin to
        assertEquals(learned.copy(commit = ""), t.apply(Pin(0)).copy(commit = ""))
        val forgot = t.apply(Forget(0))
        assertEquals(listOf("工", "恭恭敬敬"), forgot.candidates)
        assertEquals("aaaa", forgot.preedit)
        // a phrase saved, forgotten: offered no more
        t.apply(Reset)
        t.type("wqiy")
        t.type("wun")
        t.type("wqwu")
        assertEquals("你们", t.apply(Select(0)).commit)
        assertEquals(listOf("你们"), t.type("wqw").second.candidates)
        assertTrue(t.apply(Forget(0)).candidates.isEmpty())
        // nothing to forget: nothing typed
        t.apply(Reset)
        assertFalse(t.apply(Forget(0)).handled)
    }

    @Test
    fun aKeyNotReadBreaksAPhrase() {
        val t = session()
        t.type("wqiy")
        val comma = t.apply(Key(','))
        assertFalse(comma.handled)
        t.type("wun")
        assertFalse("你们" in t.type("wqwu").second.candidates)
    }

    @Test
    fun nothingIsLearnedWhileLearningIsOff() {
        val t = session()
        t.learning = false
        t.type("wqiy")
        t.type("wun")
        assertFalse("你们" in t.type("wqwu").second.candidates)
        // a pick moves what is picked up, unless learning is off
        for (learning in listOf(true, false)) {
            val u = session()
            u.learning = learning
            u.type("aaaa")
            assertEquals("恭恭敬敬", u.apply(Select(1)).commit)
            assertEquals(if (learning) "恭恭敬敬" else "工", u.type("aaaa").second.candidates.first())
        }
    }

    @Test
    fun orTypedCharacterByCharacterOftenEnough() {
        val t = session()
        repeat(2) {
            t.type("wqiy")
            t.type("wun")
        }
        assertEquals("", t.type("wqwu").first)
        t.apply(Reset)
        t.type("wqiy")
        t.type("wun")
        // seen three times
        assertEquals("你们", t.type("wqwu").first)
    }

    @Test
    fun aPhraseCommittedWholeStartsNoPhrase() {
        val t = session()
        t.type("wqvb")
        t.type("wun")
        // wqw leads nowhere, so what wq offers first goes and wu is typed on
        val (commit, s) = t.type("wqwu")
        assertEquals("你好", commit)
        assertEquals(listOf("们"), s.candidates)
    }

    @Test
    fun phrasesTheTableHasAreNotLearnedAgainAndResetForgetsWhatCameBefore() {
        val t = session()
        t.type("wqiy")
        t.type("vbg")
        // 你好 is in the table already, as itself
        assertEquals(listOf("你好"), t.type("wqv").second.candidates)
        t.apply(Reset)
        t.type("wqiy")
        t.apply(Reset)
        t.type("wun")
        assertFalse("你们" in t.type("wqwu").second.candidates)
        // not learned without the option
        val none = session(TableOptions.CANGJIE.copy(autoPhraseLength = 0))
        none.type("wqiy")
        none.type("wun")
        assertEquals(emptyList<String>(), none.type("wqwu").second.candidates)
    }

    /** Reads keys as pinyin the dumb way: "ni" is 你, "wo" 我 then 窝; ' first it refuses. */
    private class FakePinyin : Session {
        val typed = StringBuilder()
        var resets = 0
        // "nini": 你 picked from the start, "ni" left
        override var learning = true
        var picked = false

        private fun candidates() = when (typed.toString()) {
            "ni" -> listOf("你")
            "wo" -> listOf("我", "窝")
            else -> emptyList()
        }

        override fun reads(c: Char) = c in 'a'..'z'

        override fun candidates(from: Int, count: Int) = candidates().drop(from).take(count).map { Choice(it) }
        override fun offers(index: Int) = setOf(Offer.FORGET, Offer.PIN)

        private fun snap(commit: String = "", handled: Boolean = true) =
            Snapshot(commit, typed.toString(), candidates(), 0, false, false, handled, predicting = commit.isNotEmpty())

        override fun apply(action: Action): Snapshot = when (action) {
            is Key -> if (typed.isEmpty() && action.char == '\'') snap(handled = false) else typed.append(action.char).let { snap() }
            Backspace -> typed.setLength(typed.length - 1).let { snap() }
            is Select -> if (typed.toString() == "nini") {
                picked = true
                typed.setLength(2)
                snap()
            } else {
                candidates().getOrNull(action.index)?.let { c -> typed.setLength(0); snap(c) } ?: snap(handled = false)
            }
            CommitRaw -> ("你".takeIf { picked }.orEmpty() + typed).also { typed.setLength(0) }.let { snap(it) }
            Reset -> snap().also { typed.setLength(0); resets++ }
            else -> snap()
        }
    }

    @Test
    fun thePinyinKeyLooksCharactersUpByPinyinShowingTheirCode() {
        val pinyin = FakePinyin()
        val t = session(pinyin = pinyin)
        assertEquals("z", t.type("z").second.preedit)
        val (_, s) = t.type("wo")
        assertEquals(listOf("我", "窝"), s.candidates)
        // 窝 is not in the table and has no rule for one character
        assertEquals(listOf("trnt", ""), s.hints)
        assertFalse(s.predicting)
        // what pinyin learned is forgotten, but no phrase of its own pinned
        assertEquals(setOf(Offer.FORGET), t.offers(0))
        assertEquals(s, t.apply(Pin(0)))
        val picked = t.apply(Select(0))
        assertEquals("我", picked.commit)
        assertEquals(1, pinyin.resets)
        // back to codes
        assertEquals(listOf("工", "戈"), t.type("a").second.candidates.take(2))
    }

    @Test
    fun aPinyinLookupEndsByBackspaceEnterOrAnotherKey() {
        val pinyin = FakePinyin()
        val t = session(pinyin = pinyin)
        t.type("zn")
        assertEquals("z", t.apply(Backspace).preedit)
        assertEquals("", t.apply(Backspace).preedit)
        assertFalse(pinyin.typed.isNotEmpty())
        t.type("zni")
        assertEquals("zni", t.apply(CommitRaw).commit)
        t.type("zni")
        val s = t.apply(Key(','))
        assertEquals("你", s.commit)
        assertFalse(s.handled)
        t.type("zq")
        assertEquals("q", t.apply(Key('.')).commit)
        t.type("z")
        assertEquals("", t.apply(Select(0)).preedit)
        t.type("zwo")
        t.apply(NextPage)
        assertEquals("窝", t.apply(Select(1)).commit)
        t.type("zni")
        assertEquals("", t.apply(Reset).preedit)
        assertEquals("", pinyin.typed.toString())
    }

    @Test
    fun thePinyinKeyAfterACodeItCommitsStartsALookup() {
        val pinyin = FakePinyin()
        val t = session(pinyin = pinyin)
        val (commit, s) = t.type("kkkkz")
        assertEquals("叫", commit)
        assertEquals("z", s.preedit)
        assertEquals(listOf("你"), t.type("ni").second.candidates)
        assertEquals("ni", pinyin.typed.toString())
    }

    @Test
    fun enterDuringALookupKeepsWhatWasPicked() {
        val t = session(pinyin = FakePinyin())
        t.type("znini")
        t.apply(Select(0))
        assertEquals("你ni", t.apply(CommitRaw).commit)
        // the pinyin key alone is committed as typed
        t.type("z")
        assertEquals("z", t.apply(CommitRaw).commit)
    }

    @Test
    fun aKeyThePinyinSessionRefusesIsSwallowed() {
        val pinyin = FakePinyin()
        val t = session(pinyin = pinyin)
        t.type("z")
        val s = t.apply(Key('\''))
        assertTrue(s.handled)
        assertEquals("z", s.preedit)
        // so backspace ends the lookup, rather than reaching a pinyin session with nothing typed
        assertEquals("", t.apply(Backspace).preedit)
        assertEquals("工", t.type("a").second.candidates.first())
    }

    @Test
    fun withoutAPinyinSessionThePinyinKeyIsAWildcard() {
        assertEquals(listOf("工", "戈", "人"), session().type("z").second.candidates)
    }

    @Test
    fun aTableWithoutACodeLengthOrKeysIsRefused() {
        assertThrows(DataFormatException::class.java) { dictionary(header = mapOf("码长" to "0")) }
        assertThrows(DataFormatException::class.java) { dictionary(header = mapOf("键码" to "")) }
    }

    @Test
    fun codesStartingWithAnAvoidedKeyBuildNoPhrase() {
        val d = dictionary("wqiy 你", "wun 们", header = mapOf("规避字符" to "w"))
        assertEquals(null, d.encode("你们"))
        assertEquals(null, d.codeOf("你"))
    }

    @Test
    fun aWildcardInTheCodeLeadsOnByTheStartOfLongerCodes() {
        // wz is no code, but starts wqiy: typing on
        assertEquals("", session().type("wz").first)
        assertTrue(wubi.hasMatch("wz", 'z'))
        assertTrue(wubi.match("wz", 'z').isEmpty())
        assertFalse(wubi.hasMatch("wzzzz", 'z'))
    }

    @Test
    fun theConstructCodeIsWhatPhrasesAreBuiltFrom() {
        val d = dictionary("wqiy 你", "^abcd 你", "wun 们", header = mapOf("构词" to "^"))
        // with 构词 codes, only those build phrases: 们 has none
        assertEquals(null, d.encode("你们"))
        val both = dictionary("wqiy 你", "^abcd 你", "wun 们", "^wunn 们", header = mapOf("构词" to "^"))
        assertEquals("abwu", both.encode("你们"))
        assertEquals("wqiy", both.codeOf("你"))
        assertEquals("abwu", both.codeOf("你们"))
        // a wildcard never turns up a marked entry
        assertEquals(listOf("你"), TableSession(d, TableOptions.CANGJIE).type("****").second.candidates)
        assertTrue(d.match("*****", '*').isEmpty())
        assertTrue(d.contains("wqiy", "你"))
        assertFalse(d.contains("wqiy", "们"))
    }

    private val wubiPinyin = dictionary(
        "a 工", "aa 式", "ai 蛙", "@a 啊", "@ai 爱", "@ai 哀", "@gong 公", "@gong 工", "@zhong 中", "@zhongguo 中国",
        // few keys, so that most letters are pinyin's alone
        header = mapOf("拼音" to "@", "键码" to "aiy"),
    )

    @Test
    fun aTablesOwnPinyinEntriesComeAfterItsCodes() {
        val t = TableSession(wubiPinyin, TableOptions.WUBI_PINYIN)
        // one key: only what it spells whole, as libime has it
        assertEquals(listOf("工", "啊", "式", "蛙"), t.type("a").second.candidates)
        t.apply(Reset)
        // then those it starts too, a code as long first
        assertEquals(listOf("蛙", "爱", "哀"), t.type("ai").second.candidates)
        t.apply(Reset)
        // g is no key of the table's codes, but leads to pinyin: showing the code to learn
        val gong = t.type("gong")
        assertEquals("", gong.first)
        assertEquals(listOf("公", "工"), gong.second.candidates)
        assertEquals(listOf("", "a"), gong.second.hints)
        assertTrue(t.reads('g'))
    }

    @Test
    fun pinyinHasNoLengthLimitAndCommitsOnlyWhenPicked() {
        val t = TableSession(wubiPinyin, TableOptions.WUBI_PINYIN)
        // z, the wildcard, is a letter of pinyin as it is; past 码长 a code goes on
        val (commit, s) = t.type("zhongguo")
        assertEquals("", commit)
        assertEquals("zhongguo", s.preedit)
        assertEquals(listOf("中国"), s.candidates)
        assertEquals("中国", t.apply(Select(0)).commit)
        // a key leading nowhere commits the first, as for codes
        assertEquals("中", t.type("zhongx").first)
    }

    @Test
    fun aPinyinEntryIsNeverFirstWhenACodeHasACandidate() {
        val d = dictionary("aa 式", "@a 啊", header = mapOf("拼音" to "@"))
        val options = TableOptions.WUBI_PINYIN.copy(noSortInputLength = 0)
        // 啊's pinyin is the shorter, yet 式 leads
        assertEquals(listOf("式", "啊"), TableSession(d, options).type("a").second.candidates)
    }

    @Test
    fun twoKeysOfPinyinFindWhatTheyStart() {
        assertEquals(listOf("中", "中国"), TableSession(wubiPinyin, TableOptions.WUBI_PINYIN).type("zh").second.candidates)
    }

    @Test
    fun whatPinyinFoundIsNotLearned() {
        val user = TableUser(wubiPinyin)
        val t = TableSession(wubiPinyin, TableOptions.WUBI_PINYIN, user = user)
        t.type("ai")
        assertEquals("哀", t.apply(Pick(2)).commit)
        // 工 has a code, so two characters in a row could build a phrase
        t.type("gong")
        assertEquals("工", t.apply(Pick(1)).commit)
        var records = 0
        user.forEachRecord { records++ }
        assertEquals(0, records)
        assertEquals(listOf("蛙", "爱", "哀"), t.type("ai").second.candidates)
    }

    @Test
    fun aMarkerWithoutEntriesIsNoPinyin() {
        val d = dictionary("aaaa 工", "aaab 式", header = mapOf("拼音" to "@"))
        assertEquals(null, d.pinyinMarker)
        assertTrue(d.matchPinyin("a", prefix = true).isEmpty())
        // so a full code commits the first on the next key, as without the marker
        assertEquals("工", TableSession(d, TableOptions.WUBI_PINYIN).type("aaaaa").first)
        assertEquals(listOf(4), wubiPinyin.matchPinyin("gong", prefix = false).map { wubiPinyin.codeLength(it) - 1 }.distinct())
    }

    @Test
    fun anEndKeyEndsTheCode() {
        val d = dictionary(
            "a, 挨", "a, 哎", "a,b 暗", "a,bc 按", "b 不", "bc 部",
            header = mapOf("键码" to "abc,"),
        )
        val (commit, s) = TableSession(d, TableOptions.WANFENG).type("a,b")
        assertEquals("挨", commit)
        assertEquals("b", s.preedit)
        // without it, a,b is a code going on
        assertEquals("a,b", TableSession(d, TableOptions.WANFENG.copy(endKeys = "")).type("a,b").second.preedit)
    }

    @Test
    fun aKeyNoCodeStartsIsTheApps() {
        // 晚风's , and . start no code: they are its punctuation
        val d = dictionary("a, 挨", "a, 哎", "b 不", header = mapOf("键码" to "ab,"))
        val t = TableSession(d, TableOptions.WANFENG)
        val alone = t.apply(Key(','))
        assertFalse(alone.handled)
        assertEquals("", alone.preedit)
        val (commit, s) = t.type("a,,")
        assertEquals("挨", commit)
        assertFalse(s.handled)
        assertEquals("", s.preedit)
    }

    @Test
    fun selectionKeysPickWhileACodeIsTyped() {
        val d = dictionary("0 一", "0 丁", "00 七", "1 三", header = mapOf("键码" to "0123456789"))
        val t = TableSession(d, TableOptions.DIANBAO)
        assertFalse(t.reads('w'))
        val typed = t.type("0").second
        assertEquals(listOf("一", "丁", "七"), typed.candidates)
        // labelled by the keys that pick them
        assertEquals("qwertyuiop", typed.labels)
        assertEquals("", session().type("a").second.labels)
        assertTrue(t.reads('w'))
        assertFalse(t.reads('a'))
        assertEquals("丁", t.apply(Key('w')).commit)
        // beyond the page: nothing
        t.type("0")
        assertEquals("", t.apply(Key('p')).commit)
        assertEquals("0", t.apply(Key('p')).preedit)
        // with no candidate to pick, a selection key is any other key
        val none = TableSession(d, TableOptions.DIANBAO.copy(noMatchAutoSelectLength = 0))
        assertEquals("05", none.type("05").second.preedit)
        val passed = none.apply(Key('w'))
        assertFalse(passed.handled)
        assertEquals("", passed.preedit)
    }

    @Test
    fun aSelectionKeyPicksBeforeItTypes() {
        val d = dictionary("a 工", "a 式", "aa 戈")
        val t = TableSession(d, TableOptions.WUBI.copy(selectionKeys = "as"))
        t.type("a")
        assertEquals("式", t.apply(Key('s')).commit)
    }
}
