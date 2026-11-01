/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.HandedBack
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The table's 拼 key ([Action.LookUp]): every letter looked up by pinyin till it is pressed again. */
class TableLookUpModeTest {

    /**
     * Shows what is typed; 我 for wo, 你 for ni, picked by a space, 你 of nihao a piece; Enter gives
     * the keys. What it would learn, [learned]: commits while learning, a piece handed back.
     */
    private class Pinyin : Session {
        private val typed = StringBuilder()
        private var pieces = ""
        val learned = ArrayList<String>()
        override var learning = true

        override fun reads(c: Char) = c in 'a'..'z' || c == '\''
        override fun candidates(from: Int, count: Int) = emptyList<Choice>()
        override fun offers(index: Int) = emptySet<Offer>()

        override fun apply(action: Action): Snapshot {
            var commit = ""
            when (action) {
                is Action.Key -> typed.append(action.char)
                Action.Backspace -> if (typed.isNotEmpty()) typed.setLength(typed.length - 1)
                is Action.Select -> {
                    val whole = WORDS[typed.toString()]
                    val piece = WORDS.entries.firstOrNull { typed.startsWith(it.key) }
                    if (whole != null) {
                        commit = pieces + whole
                        pieces = ""
                        typed.setLength(0)
                    } else if (piece != null) {
                        pieces += piece.value
                        typed.delete(0, piece.key.length)
                    }
                }
                Action.CommitRaw, Action.Reset -> {
                    if (action == Action.CommitRaw) commit = pieces + typed
                    pieces = ""
                    typed.setLength(0)
                }
                else -> Unit
            }
            if (commit.isNotEmpty() && learning) learned += commit
            val candidates = listOfNotNull(WORDS[typed.toString()])
            return Snapshot(commit, pieces + typed, candidates, 0, hasPreviousPage = false, hasNextPage = false, handled = true, predicting = false)
        }

        override fun handBack(): HandedBack {
            if (pieces.isNotEmpty() && learning) learned += pieces
            return HandedBack(pieces, null, typed.toString()).also {
                pieces = ""
                typed.setLength(0)
            }
        }

        companion object {
            val WORDS = mapOf("wo" to "我", "ni" to "你")
        }
    }

    private fun wubi(mode: TableSession.LookUpMode = TableSession.LookUpMode(), options: TableOptions = TableOptions.WUBI, pinyin: Pinyin = Pinyin()) =
        TableSession(dictionary("trnt 我", "wqiy 你", "aawt 工作", "haob 好", "xiab 系"), options, pinyin, lookUpMode = mode)

    @Test
    fun onEveryLetterIsLookedUpTillItIsPressedAgain() {
        val mode = TableSession.LookUpMode()
        val t = wubi(mode)
        assertTrue(t.apply(Action.LookUp).handled)
        assertTrue(mode.on)
        val (_, wo) = t.type("wo")
        assertTrue(wo.lookingUp)
        assertEquals("wo", wo.preedit)
        assertEquals(listOf("我"), wo.candidates)
        // its code beside it
        assertEquals(listOf("trnt"), wo.hints)
        assertEquals("我", t.apply(Action.Select(0)).commit)
        // still on: the next letter looks up too
        val n = t.apply(Action.Key('n'))
        assertTrue(n.lookingUp)
        assertEquals("n", n.preedit)
        // off: a code again
        t.apply(Action.Reset)
        t.apply(Action.LookUp)
        assertFalse(mode.on)
        val (_, code) = t.type("wq")
        assertFalse(code.lookingUp)
        assertEquals("wq", code.preedit)
    }

    @Test
    fun whatIsTypedGoesOverAsItIsPressed() {
        val t = wubi()
        t.type("wq")
        // a code into a lookup
        val looked = t.apply(Action.LookUp)
        assertTrue(looked.lookingUp)
        assertEquals("wq", looked.preedit)
        // the lookup back into a code
        val code = t.apply(Action.LookUp)
        assertFalse(code.lookingUp)
        assertEquals("wq", code.preedit)
        assertEquals(listOf("你"), code.candidates)
        assertTrue(code.handled)
        // a code it spells whole waits for a pick: nothing is committed by the key
        t.apply(Action.Reset)
        t.apply(Action.LookUp)
        t.type("aawt")
        val whole = t.apply(Action.LookUp)
        assertEquals("", whole.commit)
        assertEquals(listOf("工作"), whole.candidates)
        // as many as the longest code takes
        t.apply(Action.Reset)
        t.apply(Action.LookUp)
        t.type("aawtwq")
        assertEquals("aawt", t.apply(Action.LookUp).preedit)
    }

    @Test
    fun aLookUpItsKeyStartedStaysOne() {
        val mode = TableSession.LookUpMode()
        val t = wubi(mode)
        t.type("zwo")
        val on = t.apply(Action.LookUp)
        assertTrue(on.lookingUp)
        assertEquals("wo", on.preedit)
        val off = t.apply(Action.LookUp)
        assertTrue(off.lookingUp)
        assertFalse(mode.on)
        assertEquals("我", t.apply(Action.Select(0)).commit)
    }

    @Test
    fun theLastLetterDeletedEndsItAndEnterGivesTheLettersAlone() {
        val t = wubi()
        t.apply(Action.LookUp)
        t.type("w")
        val gone = t.apply(Action.Backspace)
        assertFalse(gone.lookingUp)
        assertEquals("", gone.preedit)
        assertTrue(gone.handled)
        t.type("wo")
        // no key to give back before them
        assertEquals("wo", t.apply(Action.CommitRaw).commit)
        // a key the app takes commits what was looked up, then is the app's
        t.type("wo")
        val comma = t.apply(Action.Key(','))
        assertEquals("我", comma.commit)
        assertFalse(comma.handled)
    }

    @Test
    fun theModeOutlivesASessionMadeAnewAndATableWithNoPinyinHasNone() {
        val mode = TableSession.LookUpMode()
        wubi(mode).apply(Action.LookUp)
        // the settings changed: a session made anew, the same 拼 key
        assertTrue(wubi(mode).type("ni").second.lookingUp)
        // 仓颉 has no lookup key of its own: its 拼 key looks up all the same
        val cangjie = TableSession(dictionary("onf 你", header = mapOf("键码" to "abcdefghijklmnopqrstuvwxyz")), TableOptions.CANGJIE, Pinyin(), lookUpMode = TableSession.LookUpMode())
        cangjie.apply(Action.LookUp)
        val ni = cangjie.type("ni").second
        assertEquals(listOf("你"), ni.candidates)
        // its code as its keys show it
        assertEquals(listOf("人弓火"), ni.hints)
        // none: nothing to turn on
        val alone = TableSession(dictionary("trnt 我"), TableOptions.WUBI)
        alone.apply(Action.LookUp)
        assertFalse(alone.type("wo").second.lookingUp)
    }

    @Test
    fun theKeysLookupIsMarkedAndCangjiesCodeIsShownInRoots() {
        val z = wubi().type("z").second
        assertTrue(z.lookingUp)
        assertEquals("z", z.preedit)
        val cangjie = TableSession(dictionary("ab 明", "a 日", header = mapOf("键码" to "abcdefghijklmnopqrstuvwxyz")), TableOptions.CANGJIE.copy(autoSelect = false))
        val a = cangjie.type("a").second
        assertEquals("日", a.preedit)
        assertFalse(a.lookingUp)
        assertEquals("日月", cangjie.apply(Action.Key('b')).preedit)
        // the cursor where it was: one root a letter
        assertEquals(1, cangjie.apply(Action.Cursor(1)).cursor)
        // what is committed as typed is the letters
        assertEquals("ab", cangjie.apply(Action.CommitRaw).commit)
    }

    // off mid-lookup: nothing of the pinyin committed nor learned, the letters a code again (review b5 MUST)
    @Test
    fun offTheLettersAreACodeAgainAndNothingIsLearned() {
        val pinyin = Pinyin()
        val t = wubi(pinyin = pinyin)
        t.apply(Action.LookUp)
        t.type("hao")
        val off = t.apply(Action.LookUp)
        assertEquals("", off.commit)
        assertEquals("hao", off.preedit)
        assertFalse(off.lookingUp)
        assertEquals(emptyList<String>(), pinyin.learned)
        // a ' has no place in a code: the letters alone
        t.apply(Action.Reset)
        t.apply(Action.LookUp)
        t.type("xi'a")
        assertEquals("xia", t.apply(Action.LookUp).preedit)
        assertEquals(emptyList<String>(), pinyin.learned)
    }

    // 二笔's codes have a ', the pinyin's is a separator all the same
    @Test
    fun offThePinyinsApostropheIsDroppedWhereACodeHasOne() {
        val erbi = TableSession(
            dictionary("xian 先", "x'an 某", header = mapOf("键码" to "abcdefghijklmnopqrstuvwxyz;',./")),
            TableOptions.ERBI, Pinyin(), lookUpMode = TableSession.LookUpMode(),
        )
        erbi.apply(Action.LookUp)
        erbi.type("xi'an")
        assertEquals("xian", erbi.apply(Action.LookUp).preedit)
    }

    // a word picked of it is put in, as a pick is, and learned so; the letters after it a code again
    @Test
    fun offAWordPickedOfItIsPutInAndTheRestIsACode() {
        val pinyin = Pinyin()
        val t = wubi(pinyin = pinyin)
        t.apply(Action.LookUp)
        t.type("nihao")
        val piece = t.apply(Action.Select(0))
        assertEquals("", piece.commit)
        assertEquals("你hao", piece.preedit)
        val off = t.apply(Action.LookUp)
        assertEquals("你", off.commit)
        assertEquals("hao", off.preedit)
        assertFalse(off.lookingUp)
        assertEquals(listOf("你"), pinyin.learned)
    }

    @Test
    fun onACodeWithAWildcardPastItsFirstKeyStaysOneUnlessTheTableSpellsPinyin() {
        class Row(val why: String, val table: () -> TableSession, val keys: String, val looksUp: Boolean)
        assertRows(
            listOf(
                Row(
                    "仓颉's wildcard: no pinyin spells it",
                    { TableSession(dictionary("ab 明", "a 日", header = mapOf("键码" to "abcdefghijklmnopqrstuvwxyz")), TableOptions.CANGJIE.copy(autoSelect = false), Pinyin(), lookUpMode = TableSession.LookUpMode()) },
                    "a*", false,
                ),
                // t, as trnt 我 starts: a code the table has (a key no code starts is the app's, and z after it would look up)
                Row("五笔's wildcard is a letter, z: past the first key it is still a code", { wubi() }, "tz", false),
                Row(
                    "五笔拼音's z past the first key is pinyin's: looked up",
                    { TableSession(dictionary("trnt 我", "@wo 我", header = mapOf("拼音" to "@")), TableOptions.WUBI_PINYIN, Pinyin(), lookUpMode = TableSession.LookUpMode()) },
                    "tz", true,
                ),
            ),
            { it.why },
        ) { row ->
            val t = row.table()
            t.type(row.keys)
            assertEquals(row.looksUp, t.apply(Action.LookUp).lookingUp)
        }
    }
}
