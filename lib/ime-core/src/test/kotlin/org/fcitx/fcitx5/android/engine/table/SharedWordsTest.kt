/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.session.Action.Block
import org.fcitx.fcitx5.android.engine.session.Action.Forget
import org.fcitx.fcitx5.android.engine.session.Offer
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedWordsTest {

    private val wubi = dictionary("wqiy 你", "vbg 好", "wqvb 你好", "trnt 我", "wu 们", "wuyy 门", "wqwu 你们")

    /** The other input methods as a test sees them: [words] shared, what was blocked and forgotten recorded. */
    private class Fake(table: TableDictionary, private val words: List<String>) : SharedWords {
        val coded = CodedWords(table, words)
        val blocked = HashSet<String>()
        val forgotten = ArrayList<String>()
        override fun words(prefix: String) = coded.words(prefix).filterNot { it.second in forgotten }
        override fun leadsAnywhere(prefix: String) = coded.leadsAnywhere(prefix)
        override fun blocked(text: String) = text in blocked
        override fun blockable(text: String) = text.length >= 2
        override fun block(text: String) = blocked.add(text)
        override fun forget(text: String) {
            forgotten += text
        }
        // the user's words: learned, till forgotten
        override fun learned(text: String) = text in words && text !in forgotten
    }

    @Test
    fun theUsersWordsAreCodedByTheRulesNoneTheTableHasSo() {
        // 你好 the table's own; 我们 trwu, listed twice, coded once; 们 a character; 好你 vbwq; x has
        // no code
        val coded = CodedWords(wubi, listOf("你好", "我们", "们", "好你", "我们", "x你"))
        assertEquals(listOf("trwu" to "我们"), coded.words("tr"))
        assertEquals(listOf("vbwq" to "好你"), coded.words("vbw"))
        assertEquals(2, coded.size)
        assertEquals(true, coded.leadsAnywhere("t"))
        assertEquals(emptyList<Pair<String, String>>(), coded.words(""))
    }

    @Test
    fun sharedWordsComeAfterTheTablesOwnAndBlockedOnesNowhere() {
        val shared = Fake(wubi, listOf("你门", "你们"))
        val session = TableSession(wubi, TableOptions.WUBI, user = TableUser(wubi), shared = shared)
        // 你门 is wqwu as 你们 is: the table's first
        assertEquals(listOf("你们", "你门"), session.type("wqwu").second.candidates)
        assertEquals(setOf(Offer.FORGET, Offer.BLOCK), session.offers(1))
        assertEquals(listOf("你们"), session.apply(Block(1)).candidates)
        assertEquals(setOf("你门"), shared.blocked)
    }

    @Test
    fun forgottenAWordIsForgottenByAllTheTablesOwnToo() {
        // what a table puts in, every input method learned (the one user model): forgotten by all
        val shared = Fake(wubi, listOf("你门"))
        val session = TableSession(wubi, TableOptions.WUBI, user = TableUser(wubi), shared = shared)
        session.type("wqwu")
        session.apply(Forget(0))
        assertEquals(listOf("你们"), shared.forgotten)
        assertEquals(listOf("你们"), session.apply(Forget(1)).candidates)
        assertEquals(listOf("你们", "你门"), shared.forgotten)
    }

    @Test
    fun codingStopsAtACheckOnceNoLongerWanted() {
        // the word past the first STOP_CHECK texts: coded only while still wanted at that check
        val texts = List(CodedWords.STOP_CHECK) { "x你" } + "我们"
        assertEquals(1, CodedWords(wubi, texts).size)
        assertEquals(1, CodedWords(wubi, texts) { true }.size)
        // wanted at the first check, not at the one before the word
        var checks = 0
        assertEquals(0, CodedWords(wubi, texts) { checks++ == 0 }.size)
        assertEquals(0, CodedWords(wubi, listOf("我们")) { false }.size)
    }
}
