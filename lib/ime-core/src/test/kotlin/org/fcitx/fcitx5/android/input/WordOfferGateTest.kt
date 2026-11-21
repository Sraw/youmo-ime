/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.session.OfferedWord
import org.fcitx.fcitx5.android.engine.session.WordOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordOfferGateTest {
    private val offer = WordOffer(1, listOf(OfferedWord("汪小明", "iije")))

    @Test
    fun thePauseIsTimedOnceNoFingerIsOnTheKeys() {
        val gate = WordOfferGate()
        gate.down()
        // the key's commit comes as the finger is still down
        assertFalse(gate.offered(offer))
        gate.down()
        assertFalse(gate.up())
        assertNull(gate.due(noticeShown = false, touchExploring = false, letters = true) { "汪小明" })
        assertTrue(gate.up())
        assertEquals(WordOfferGate.Due(1, listOf(IndexedValue(0, offer.words[0]))), gate.due(noticeShown = false, touchExploring = false, letters = true) { "汪小明" })
        // at most once
        assertNull(gate.due(noticeShown = false, touchExploring = false, letters = true) { "汪小明" })
        assertFalse(gate.up())
    }

    @Test
    fun anOfferIsShownOnlyOverNothingWithTheLettersUpAndNoScreenReaderExploring() {
        class Row(val why: String, val noticeShown: Boolean = false, val touchExploring: Boolean = false, val letters: Boolean = true, val shown: Boolean)
        assertRows(
            listOf(
                Row("nothing in the way", shown = true),
                Row("another notice, maybe with its 撤销, stays", noticeShown = true, shown = false),
                Row("TalkBack would read it after every character", touchExploring = true, shown = false),
                Row("symbols or emoji shown", letters = false, shown = false),
            ),
            { it.why },
        ) { row ->
            val gate = WordOfferGate()
            assertTrue(gate.offered(offer))
            assertEquals(row.shown, gate.due(row.noticeShown, row.touchExploring, row.letters) { "汪小明" } != null)
        }
    }

    // a tap is checked against the field's text: where it reads back other text, "" or nothing, the word would do nothing
    @Test
    fun onlyWordsTheFieldReadsBackBeforeTheCursorAreShown() {
        val two = WordOffer(2, listOf(OfferedWord("汪小明", "iije"), OfferedWord("小明", "ihje")))
        class Row(val why: String, val before: String?, val shown: List<Pair<Int, String>>?)
        assertRows(
            listOf(
                Row("as written: both", "你好汪小明", listOf(0 to "汪小明", 1 to "小明")),
                Row("only the last two characters read back: the second, its index kept for the tap", "小明", listOf(1 to "小明")),
                Row("reads back empty", "", null),
                Row("does not read at all", null, null),
                Row("other text than written", "汪小", null),
            ),
            { it.why },
        ) { row ->
            val gate = WordOfferGate()
            gate.offered(two)
            assertEquals(row.shown, gate.due(noticeShown = false, touchExploring = false, letters = true) { row.before }?.words?.map { it.index to it.value.text })
        }
    }

    @Test
    fun withdrawnOrOvertakenByANoticeItIsNotShown() {
        val gate = WordOfferGate()
        gate.offered(offer)
        assertFalse(gate.offered(null))
        assertNull(gate.due(noticeShown = false, touchExploring = false, letters = true) { "汪小明" })
        gate.offered(offer)
        gate.noticed()
        assertNull(gate.due(noticeShown = false, touchExploring = false, letters = true) { "汪小明" })
        gate.down()
        gate.released()
        assertTrue(gate.offered(offer))
    }

    // the next key often comes as it goes up: a flash is no look, so its words may come again in the field
    @Test
    fun anOfferIsSeenOnlyOnceUpASecond() {
        class Row(val why: String, val seen: Int?, val at300ms: WordOfferGate.() -> Unit)
        assertRows(
            listOf(
                Row("still up", 1) {},
                Row("the next key or a change in the field withdrew it", null) { offered(null) },
                Row("withdrawn for another offer", null) { offered(offer.copy(id = 2)) },
                Row("another notice took its place", null) { noticed() },
                Row("put away under a menu", null) { hidden() },
            ),
            { it.why },
        ) { row ->
            val gate = WordOfferGate()
            gate.offered(offer)
            assertEquals(1, gate.due(noticeShown = false, touchExploring = false, letters = true) { "汪小明" }?.id)
            gate.shown(1, now = 5000)
            row.at300ms(gate)
            assertEquals(row.seen, gate.seen(now = 5000 + WordOfferGate.SEEN_MS))
        }
    }

    @Test
    fun seenFromASecondUpAndToldOnce() {
        val gate = WordOfferGate()
        gate.shown(1, now = 0)
        assertNull(gate.seen(now = WordOfferGate.SEEN_MS - 1))
        assertEquals(1, gate.seen(now = WordOfferGate.SEEN_MS))
        assertNull("told once", gate.seen(now = WordOfferGate.SEEN_MS * 2))
        // an earlier showing's check comes too soon for the one up now
        gate.shown(2, now = 900)
        assertNull(gate.seen(now = WordOfferGate.SEEN_MS))
        assertEquals(2, gate.seen(now = 900 + WordOfferGate.SEEN_MS))
    }
}
