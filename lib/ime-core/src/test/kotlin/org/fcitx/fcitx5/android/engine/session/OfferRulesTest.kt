/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferRulesTest {
    // each question's answer, null for one that must not be asked
    private class Row(
        val why: String,
        val learning: Boolean,
        val forgets: Boolean?,
        val pins: Boolean?,
        val pinned: Boolean?,
        val blocks: Boolean?,
        val phrase: Boolean,
        val offers: Set<Offer>,
    )

    // what the rules offer, and each question asked at most once and only where it decides something
    @Test
    fun eachOfferAsItsQuestionIsAnsweredEachAskedOnlyWhenItDecides() = assertRows(
        listOf(
            Row("nothing kept: nothing asked", false, null, null, null, null, false, emptySet()),
            Row("nothing kept: a phrase still deleted", false, null, null, null, null, true, setOf(Offer.DELETE_PHRASE)),
            Row("nothing to offer", true, false, false, null, false, false, emptySet()),
            Row("every answer yes, not pinned", true, true, true, false, true, false, setOf(Offer.FORGET, Offer.PIN, Offer.BLOCK)),
            Row("pinned already: unpin", true, false, true, true, false, false, setOf(Offer.UNPIN)),
            Row("pinned asked only of what may be pinned", true, true, false, null, false, false, setOf(Offer.FORGET)),
            Row("a phrase is deleted, not blocked", true, false, false, null, null, true, setOf(Offer.DELETE_PHRASE)),
        ),
        { it.why },
    ) { row ->
        val asked = IntArray(4)
        fun answer(i: Int, value: Boolean?): () -> Boolean = {
            asked[i]++
            value ?: error("question $i asked")
        }
        val got = OfferRules.offers(
            row.learning,
            answer(0, row.forgets),
            answer(1, row.pins),
            answer(2, row.pinned),
            answer(3, row.blocks),
            row.phrase,
        )
        assertEquals(row.offers, got)
        assertTrue("asked ${asked.toList()}", asked.all { it <= 1 })
    }
}
